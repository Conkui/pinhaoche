package com.pinhaoche.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 面向其他模块的最小用户联系卡查询（RFC-012）。
 *
 * 其他模块只能取得联系人展示所需的 id、昵称和头像；openid、账号状态等身份或内部字段不得跨模块泄露。
 */
@Service
public class UserContactService {

    private final AppUserRepository userRepository;

    public UserContactService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 跨模块可传递的不可变联系卡，刻意不承载 openid、status 或完整 AppUser 实体。 */
    public record ContactCard(long id, String nickname, String avatar) {
    }

    /**
     * 批量读取联系卡，并按调用方首次提供的用户 ID 顺序返回。
     *
     * 仓储的 IN 查询没有结果顺序契约；RideService 需要稳定地把成员 ID 映射回对应展示卡，
     * 因此这里先以 LinkedHashSet 固化首次出现的顺序，再按该顺序重排查询结果。不存在或 null 的
     * ID 被忽略，重复 ID 只返回一张卡；返回 List.copyOf，避免调用方意外改写已建立的映射顺序。
     */
    @Transactional(readOnly = true)
    public List<ContactCard> findContactCards(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<Long> orderedIds = userIds.stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (orderedIds.isEmpty()) {
            return List.of();
        }

        Map<Long, AppUser> usersById = userRepository.findAllById(orderedIds).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity(), (first, ignored) -> first));

        List<ContactCard> cards = orderedIds.stream()
                .map(usersById::get)
                .filter(java.util.Objects::nonNull)
                .map(user -> new ContactCard(user.getId(), user.getNickname(), user.getAvatar()))
                .toList();
        return List.copyOf(cards);
    }
}
