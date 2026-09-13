package com.pinhaoche.importer;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.ride.RideService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 导入应用服务（Agent E 边界：Adapter/Factory/OCR Provider/解析）。
 * V1 流程（规范 §17 优先级）：官方 API（未接入）→ 分享文本/OCR（未接入）→ 用户确认 → 手动填写。
 * 当前所有平台都走"用户确认 + 手动填写"：前端把用户核对过的字段提交进来，服务端只做校验落库。
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final RideProviderFactory factory;
    private final RideService rideService;

    public ImportService(RideProviderFactory factory, RideService rideService) {
        this.factory = factory;
        this.rideService = rideService;
    }

    public record ImportRideRequest(String provider, String plateNumber,
                                    RideService.PlaceRef origin, RideService.PlaceRef destination,
                                    BigDecimal remainingDistance, Integer waitingMinutes, Integer seats) {
    }

    /** 用户确认后的字段直接创建 BOOKED ride（RFC-004）。幂等键透传保证重复提交只建一条。 */
    public RideService.RideDto importRide(long userId, ImportRideRequest request, String idempotencyKey) {
        var context = new ImportContext(request.provider());
        factory.select(context).ifPresent(adapter -> {
            var result = adapter.importRide(context);
            // 有合规 Adapter 时其结果仅用于日志/未来自动填充；落库仍以用户确认为准（规范 §18）
            log.info("平台 Adapter 返回 success={} reason={}", result.success(), result.reason());
        });

        if (request.provider() == null || request.provider().isBlank()) {
            throw ApiException.validation("provider 不能为空");
        }
        if (request.plateNumber() == null || request.plateNumber().isBlank()) {
            throw ApiException.validation("车牌号不能为空");
        }
        int seats = request.seats() == null ? 3 : request.seats();
        if (seats < 1 || seats > 8) {
            throw ApiException.validation("seats 必须在 1..8 之间");
        }
        return rideService.createBooked(userId, request.origin(), request.destination(),
                request.provider(), request.plateNumber().trim(),
                request.remainingDistance(), request.waitingMinutes(), seats, idempotencyKey);
    }
}
