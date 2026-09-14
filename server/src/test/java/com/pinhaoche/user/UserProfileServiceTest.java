package com.pinhaoche.user;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 资料更新语义测试（RFC-011）：不启动 Spring 上下文、不连数据库。
 * 前半部分是昵称净化/校验的纯函数断言（控制字符、\t \n、trim、20 字上限、净化后为空）；
 * 后半部分用真实 AppUser 实体 + mock 仓储验证"字段缺省保持原值 / 头像替换清理旧文件 / 幂等"语义。
 */
class UserProfileServiceTest {

    @TempDir
    Path tempDir;

    /** 最小 PNG：8 字节标准签名 + 填充，够魔数识别即可。 */
    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
    };

    private final AppUserRepository userRepository = mock(AppUserRepository.class);

    private UserProfileService service() {
        return new UserProfileService(userRepository, new AvatarStorage(tempDir.toString()));
    }

    /** 真实实体（id 无 setter，仅测试用反射注入），保证断言的是实体真实状态而不是 mock 桩值。 */
    private static AppUser existingUser(long id, String nickname, String avatar) {
        AppUser user = new AppUser("openid-" + id, OffsetDateTime.now().minusDays(1));
        ReflectionTestUtils.setField(user, "id", id);
        user.setNickname(nickname);
        user.setAvatar(avatar);
        return user;
    }

    @Test
    void sanitizesControlCharactersAndTrims() {
        // 剥离 0x00/0x01（不可见且会破坏下游展示），首尾空白 trim 掉
        assertThat(UserProfileService.normalizeNickname("  张\u0000\u0001三  ")).isEqualTo("张三");
        // \t 与 \n 是正常用户输入（用户可能粘贴多行/带缩进的昵称），保留但不允许出现在首尾
        assertThat(UserProfileService.normalizeNickname("\t\n 李四 \t\n")).isEqualTo("李四");
        assertThat(UserProfileService.normalizeNickname("李\t四")).isEqualTo("李\t四");
    }

    @Test
    void rejectsBlankAfterSanitizing() {
        assertThatThrownBy(() -> UserProfileService.normalizeNickname(null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> UserProfileService.normalizeNickname(""))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> UserProfileService.normalizeNickname("   \t\n "))
                .isInstanceOf(ApiException.class);
        // 纯控制字符：净化后为空，必须拒绝（否则能写进 NOT NULL 列里存"语义为空"的脏数据）
        assertThatThrownBy(() -> UserProfileService.normalizeNickname("\u0000\u0001\u0002"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void enforcesLengthByCharactersNotBytes() {
        // 按 length() 计字符：20 个汉字合法，21 个拒绝（若按字节算，20 个汉字会误判为超长）
        assertThat(UserProfileService.normalizeNickname("车".repeat(20))).hasSize(20);
        assertThatThrownBy(() -> UserProfileService.normalizeNickname("车".repeat(21)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void lengthIsMeasuredAfterSanitizing() {
        // 控制字符填充不算长度：净化后仍是 21 个字符 → 拒绝（先净化再判长度的意义所在）
        String padded = "车".repeat(21) + "\u0000".repeat(50);
        assertThatThrownBy(() -> UserProfileService.normalizeNickname(padded))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        // 长度刚好 20 但夹了大量控制字符 → 净化后合法
        assertThat(UserProfileService.normalizeNickname("车".repeat(20) + "\u0007".repeat(30)))
                .isEqualTo("车".repeat(20));
    }

    @Test
    void rejectsRequestWhereBothFieldsAreMissing() {
        UserProfileService service = service();

        // 空对象（openapi 里 requestBody required 但字段全可空）：400 且明确提示"至少提供一项"
        assertThatThrownBy(() -> service.updateProfile(1L, new UserProfileService.UpdateProfileRequest(null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.updateProfile(1L, new UserProfileService.UpdateProfileRequest("  ", "   ")))
                .isInstanceOf(ApiException.class);
        // 净化后变空的昵称同样算"未提供"：此时无任何字段可写，必须 400 而不是静默更新 updatedAt
        assertThatThrownBy(() -> service.updateProfile(1L,
                new UserProfileService.UpdateProfileRequest("\u0000\u0001", null)))
                .isInstanceOf(ApiException.class);
        // 校验失败必须发生在任何读库之前，避免无效请求也去打数据库
        verifyNoInteractions(userRepository);
    }

    @Test
    void nicknameOnlyUpdateKeepsAvatarAndRefreshesUpdatedAt() {
        AppUser user = existingUser(7L, "占位同学", null);
        OffsetDateTime before = user.getUpdatedAt();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        UserProfileService.UserProfile result = service()
                .updateProfile(7L, new UserProfileService.UpdateProfileRequest("  新昵称  ", null));

        // 净化 + trim 后入库；缺省字段保持原值（avatar 仍为 null）
        assertThat(result).isEqualTo(new UserProfileService.UserProfile(7L, "新昵称", null));
        assertThat(user.getNickname()).isEqualTo("新昵称");
        assertThat(user.getAvatar()).isNull();
        assertThat(user.getUpdatedAt()).isAfterOrEqualTo(before);
        verify(userRepository).saveAndFlush(user);
    }

    @Test
    void avatarOnlyUpdateKeepsNicknameAndDeletesOldFile() throws IOException {
        // 旧头像真的落到上传目录里，才能验证"替换后旧文件被清理"
        Path oldFile = tempDir.resolve("old.png");
        Files.writeString(oldFile, "old-bytes", StandardCharsets.UTF_8);
        AppUser user = existingUser(9L, "同学", "/files/old.png");
        when(userRepository.findById(9L)).thenReturn(Optional.of(user));

        UserProfileService.UserProfile result = service().updateProfile(9L,
                new UserProfileService.UpdateProfileRequest(null,
                        "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG)));

        assertThat(result.id()).isEqualTo(9L);
        assertThat(result.nickname()).isEqualTo("同学"); // 缺省字段保持原值
        assertThat(result.avatar()).matches("/files/[0-9a-f-]{36}\\.png"); // 服务端生成的新文件名
        assertThat(Files.exists(tempDir.resolve(result.avatar().substring("/files/".length())))).isTrue();
        assertThat(Files.exists(oldFile)).isFalse(); // 旧文件已清理，磁盘不会累积
    }

    @Test
    void unknownUserIsNotFound() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateProfile(404L,
                new UserProfileService.UpdateProfileRequest("正常昵称", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void invalidAvatarLeavesNicknameUntouchedAndWritesNothingToDb() throws IOException {
        AppUser user = existingUser(11L, "旧名字", null);
        when(userRepository.findById(11L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service().updateProfile(11L,
                new UserProfileService.UpdateProfileRequest("新名字", "不是base64!!!")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        // 头像校验失败时昵称也不能被改（整请求失败，不留半更新状态）
        assertThat(user.getNickname()).isEqualTo("旧名字");
        assertThat(user.getAvatar()).isNull();
        verify(userRepository, never()).saveAndFlush(any(AppUser.class));
    }
}

