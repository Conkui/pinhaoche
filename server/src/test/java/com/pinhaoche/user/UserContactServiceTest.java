package com.pinhaoche.user;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RFC-012 联系卡边界测试：不用 Spring/数据库，验证排序、最小字段与空输入不会触发全表查询。 */
class UserContactServiceTest {

    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final UserContactService service = new UserContactService(userRepository);

    private static AppUser user(long id, String nickname, String avatar) {
        AppUser user = new AppUser("sensitive-openid-" + id, OffsetDateTime.now());
        ReflectionTestUtils.setField(user, "id", id);
        user.setNickname(nickname);
        user.setAvatar(avatar);
        user.setStatus("ACTIVE");
        return user;
    }

    @Test
    void returnsExistingCardsInFirstRequestedOrderRatherThanRepositoryOrder() {
        AppUser first = user(7L, "小王", "/files/7.png");
        AppUser second = user(3L, "小李", null);
        // 模拟数据库 IN 查询的无序返回；服务必须自己重排，Ride 端才能稳定按成员顺序展示。
        when(userRepository.findAllById(any())).thenReturn(List.of(second, first));

        List<UserContactService.ContactCard> cards = service.findContactCards(List.of(7L, 3L, 7L, null, 99L));

        assertThat(cards).containsExactly(
                new UserContactService.ContactCard(7L, "小王", "/files/7.png"),
                new UserContactService.ContactCard(3L, "小李", null));
        // 卡片 record 只有三项公开展示数据，敏感 openid/status 不会随跨模块对象泄露。
        assertThat(UserContactService.ContactCard.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("id", "nickname", "avatar");

        ArgumentCaptor<Iterable<Long>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(userRepository).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactly(7L, 3L, 99L);
    }

    @Test
    void emptyOrOnlyNullInputReturnsImmutableEmptyListWithoutRepositoryAccess() {
        // 空输入应直接返回，不能让 findAllById(empty) 在不同 JPA 实现下演化成意外的查询。
        assertThat(service.findContactCards(null)).isEmpty();
        assertThat(service.findContactCards(List.of())).isEmpty();
        assertThat(service.findContactCards(java.util.Arrays.asList(null, null))).isEmpty();
        verify(userRepository, never()).findAllById(org.mockito.ArgumentMatchers.any());
    }
}
