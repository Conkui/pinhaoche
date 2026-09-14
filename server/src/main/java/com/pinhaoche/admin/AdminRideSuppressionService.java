package com.pinhaoche.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 管理端删除命令：先经 Ride 应用服务确认记录存在，绝不跨模块直接读取 Ride repository。 */
@Service
public class AdminRideSuppressionService {
    private final AdminRideSuppressionRepository repository;
    public AdminRideSuppressionService(AdminRideSuppressionRepository repository) {
        this.repository = repository;
    }

    public record SuppressionResult(long rideId, OffsetDateTime deletedAt, boolean alreadySuppressed) {
    }

    /**
     * 重复点删除安全幂等：保留第一次的审计时间，前端可把 alreadySuppressed 当成功处理。
     * 管理员身份由 /api/v1/admin/** 的 X-Admin-Key 拦截器统一校验，密钥本身不写入数据库或日志。
     */
    @Transactional
    public SuppressionResult suppress(long rideId) {
        return repository.findById(rideId)
                .map(existing -> new SuppressionResult(rideId, existing.getDeletedAt(), true))
                .orElseGet(() -> {
                    OffsetDateTime deletedAt = OffsetDateTime.now(ZoneOffset.UTC);
                    repository.save(new AdminRideSuppression(rideId, deletedAt));
                    return new SuppressionResult(rideId, deletedAt, false);
                });
    }

    @Transactional(readOnly = true)
    public boolean isSuppressed(long rideId) { return repository.existsById(rideId); }
}
