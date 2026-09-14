package com.pinhaoche.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 用户只读统计服务（RFC-009）：admin 模块 StatsController 跨模块只能调各模块 application service，
 * 这里以轻量服务形态暴露 user 模块的统计口径，避免 admin 直接注入 AppUserRepository。
 */
@Service
public class UserStatsService {

    private final AppUserRepository userRepository;

    public UserStatsService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public long countAll() {
        return userRepository.count();
    }

    /** "今日新增用户"计数；时间边界由调用方统一用 UTC 计算（DB 存 UTC）。 */
    @Transactional(readOnly = true)
    public long countCreatedAfter(OffsetDateTime time) {
        return userRepository.countByCreatedAtAfter(time);
    }
}
