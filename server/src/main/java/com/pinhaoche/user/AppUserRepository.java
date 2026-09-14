package com.pinhaoche.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByOpenid(String openid);

    /** RFC-009 统计：某时间点（含）之后注册的用户数，用于"今日新增用户"。 */
    long countByCreatedAtAfter(OffsetDateTime time);
}
