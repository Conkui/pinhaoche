package com.pinhaoche.importer;

import java.math.BigDecimal;

/**
 * 导入结果：success=false 时 reason 必填，走用户确认/手动填写降级。
 * 任何字段都可能缺失——低置信度字段必须让用户确认后才能落库（规范 §18）。
 */
public record RideImportResult(boolean success, String reason, String provider, String plateNumber,
                               String originText, String destinationText,
                               BigDecimal remainingDistance, Integer waitingMinutes) {

    public static RideImportResult failed(String reason) {
        return new RideImportResult(false, reason, null, null, null, null, null, null);
    }
}
