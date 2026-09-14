package com.pinhaoche.user;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.util.TextSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;

/**
 * 当前用户资料服务（RFC-011）。
 *
 * 身份约定：userId 由调用方从 token 解析后传入，本服务不接收、也不信任任何请求体内的用户标识；
 * 幂等语义：字段缺省 = 保持原值，字段存在 = 替换，同内容重复提交结果一致（不新增副作用）。
 */
@Service
public class UserProfileService {

    /** 昵称上限：与 openapi UserProfile.nickname 的 maxLength 20 对齐（列 VARCHAR(100) 有充足余量）。 */
    public static final int MAX_NICKNAME_LENGTH = 20;

    private final AppUserRepository userRepository;
    private final AvatarStorage avatarStorage;

    public UserProfileService(AppUserRepository userRepository, AvatarStorage avatarStorage) {
        this.userRepository = userRepository;
        this.avatarStorage = avatarStorage;
    }

    /** 请求体：两个字段都可空，且刻意不含任何用户 id 字段。 */
    public record UpdateProfileRequest(String nickname, String avatarBase64) {
    }

    /** 响应体：字段名与 openapi UserProfile 逐字一致，小程序可整体覆盖本地缓存。 */
    public record UserProfile(long id, String nickname, String avatar) {
    }

    /**
     * 昵称净化 + 校验的纯函数（包级可见，单测直接断言，无需 Spring 上下文）。
     *
     * 为什么先净化再判长度：长度必须按"实际入库的字符"算，否则用控制字符填充就能把真实内容
     * 长度撑过上限再被净化掉，绕过 20 字约束；而控制字符本身会在聊天/列表渲染时破坏排版。
     * 失败一律 400，不区分"空"与"超长"的具体文案给客户端做提示。
     */
    static String normalizeNickname(String raw) {
        String cleaned = TextSanitizer.clean(raw);
        if (cleaned == null || cleaned.isEmpty()) {
            throw ApiException.validation("昵称不能为空");
        }
        if (cleaned.length() > MAX_NICKNAME_LENGTH) {
            throw ApiException.validation("昵称最长 20 个字符");
        }
        return cleaned;
    }

    /**
     * 更新当前用户资料（幂等替换）。
     *
     * 顺序设计：先做"至少提供一项"判定 → 净化昵称 → 读库定位用户 → 解码落盘头像 → 写库。
     * 校验类失败（400）全部发生在写库之前；读库失败（404）发生在写盘之前，
     * 避免"用户不存在却先留下一个孤儿头像文件"。任何失败都不会产生"库里有路径、盘上没文件"的中间态。
     */
    @Transactional
    public UserProfile updateProfile(long userId, UpdateProfileRequest request) {
        // 1) 两项都缺省（null、纯空白、纯控制字符）→ 明确提示"至少提供一项"，而不是先报"昵称不能为空"。
        boolean nicknameAbsent = request.nickname() == null || TextSanitizer.clean(request.nickname()).isEmpty();
        boolean avatarAbsent = request.avatarBase64() == null || request.avatarBase64().isBlank();
        if (nicknameAbsent && avatarAbsent) {
            throw ApiException.validation("昵称或头像至少需要提供一项");
        }
        // nickname 为 null 表示"字段缺省，保持库中原值"，不会被写回。
        String nickname = nicknameAbsent ? null : normalizeNickname(request.nickname());

        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("用户不存在"));

        // 2) 头像先落盘再写库：AvatarStorage 内完成大小/魔数/文件名校验，失败即 400，此时尚未改库。
        String newAvatarPath = avatarAbsent ? null : avatarStorage.store(request.avatarBase64());

        String oldAvatarPath = user.getAvatar();
        // 3) 只覆盖非 null 字段 + 刷新 updatedAt 的逻辑收在实体方法里，避免各调用方漏刷 updatedAt。
        user.applyProfileUpdate(nickname, newAvatarPath, OffsetDateTime.now());
        // 用 saveAndFlush 而不是仅在内存里改：把 UPDATE 尽早打到数据库，写库失败（如唯一约束、
        // 连接中断）在删任何文件之前就抛出，不会出现"文件已删、库还没改"的组合。
        userRepository.saveAndFlush(user);

        if (newAvatarPath != null) {
            scheduleAvatarCleanup(oldAvatarPath, newAvatarPath);
        }
        return new UserProfile(user.getId(), user.getNickname(), user.getAvatar());
    }

    /**
     * 把旧头像文件清理挂到**事务提交之后**执行。
     *
     * 为什么不能直接在这里删（Review P1-1 修复）：本方法带 @Transactional，commit 由 Spring 事务代理在
     * 方法返回**之后**执行，saveAndFlush 只是 flush、并不提交。若在方法体内删掉旧文件，一旦提交阶段失败
     * （连接中断、容器被 kill、up --build 重启），事务回滚后库里仍指向旧路径、而文件已被删除，
     * 该用户头像将永久 404；反过来本次新落盘的文件也会变成无人引用的孤儿。
     * 因此：提交成功才删旧文件；回滚则反过来删除本次新文件（旧文件必须保住，库里还指向它）。
     */
    private void scheduleAvatarCleanup(String oldAvatarPath, String newAvatarPath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 理论上不可达（@Transactional 保证有同步器）；真无事务时按"立即提交"处理，
            // 且绝不能删新文件，最多是旧文件残留（浪费磁盘，不影响正确性）。
            if (!newAvatarPath.equals(oldAvatarPath)) {
                avatarStorage.deleteIfManaged(oldAvatarPath);
            }
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (!newAvatarPath.equals(oldAvatarPath)) {
                    avatarStorage.deleteIfManaged(oldAvatarPath);
                }
            }

            @Override
            public void afterCompletion(int status) {
                // 只在**确定回滚**时删新文件。STATUS_UNKNOWN 的语义是"提交尝试抛异常、结果未知"
                // （连接在提交后断开等），此时数据库里可能已经落库新路径——删掉新文件反而会让
                // 库指向不存在的文件（旧的已不再被引用）。UNKNOWN 一律不动，最坏只留一个孤儿文件，
                // 磁盘可清理，而误删是不可恢复的。
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    avatarStorage.deleteIfManaged(newAvatarPath);
                }
            }
        });
    }
}
