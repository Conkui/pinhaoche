package com.pinhaoche.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Collection;

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
     * 批量隐藏的汇总结果。requested 是去重后的有效订单数，因而总是等于 newlySuppressed + alreadySuppressed。
     * 这样管理端不会把重复勾选误展示为额外处理了一条订单。
     */
    public record BulkSuppressionResult(int requested, int newlySuppressed, int alreadySuppressed) {
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

    /**
     * 批量隐藏只新增审计记录，不删除 Ride，也不触碰冻结的 Ride 状态机。
     * LinkedHashSet 保留前端勾选顺序并消除重复 ID；先批量查询已有审计，避免每条订单一次查询。
     */
    @Transactional
    public BulkSuppressionResult suppressAll(List<Long> rideIds) {
        Set<Long> distinctIds = new LinkedHashSet<>(rideIds);
        if (distinctIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("rideIds 必须全部为正整数");
        }

        Set<Long> alreadySuppressed = new LinkedHashSet<>();
        for (AdminRideSuppression suppression : repository.findAllById(distinctIds)) {
            alreadySuppressed.add(suppression.getRideId());
        }

        OffsetDateTime deletedAt = OffsetDateTime.now(ZoneOffset.UTC);
        List<AdminRideSuppression> newSuppressions = distinctIds.stream()
                .filter(rideId -> !alreadySuppressed.contains(rideId))
                .map(rideId -> new AdminRideSuppression(rideId, deletedAt))
                .toList();
        if (!newSuppressions.isEmpty()) {
            repository.saveAll(newSuppressions);
        }
        return new BulkSuppressionResult(distinctIds.size(), newSuppressions.size(), alreadySuppressed.size());
    }

    @Transactional(readOnly = true)
    public boolean isSuppressed(long rideId) { return repository.existsById(rideId); }

    /**
     * 列表页一次性取得已隐藏 ID，避免 Ride 列表逐条 exists 造成 N+1 查询。
     * 仅返回审计表主键，不向 Ride 模块泄露 admin 实体。
     */
    @Transactional(readOnly = true)
    public Set<Long> suppressedRideIds(Collection<Long> rideIds) {
        if (rideIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> suppressed = new LinkedHashSet<>();
        for (AdminRideSuppression entry : repository.findAllById(rideIds)) {
            suppressed.add(entry.getRideId());
        }
        return Set.copyOf(suppressed);
    }
}
