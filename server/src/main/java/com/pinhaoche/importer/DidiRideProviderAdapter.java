package com.pinhaoche.importer;

import org.springframework.stereotype.Component;

/**
 * 滴滴 Adapter。
 * V1 现状：滴滴无对第三方开放的官方行程 API，且抓包/Hook/模拟登录被规范 §17 明确禁止，
 * 因此 importRide 一律返回失败并说明降级方式（分享文本 / 截图 OCR / 用户确认 / 手动填写）。
 * 未来若获得官方授权 API，只需替换本实现，调用方无感知（Factory + Adapter 的目的）。
 */
@Component
public class DidiRideProviderAdapter implements RideProviderAdapter {

    @Override
    public boolean supports(ImportContext context) {
        return "DIDI".equals(context.provider());
    }

    @Override
    public RideImportResult importRide(ImportContext context) {
        return RideImportResult.failed("滴滴无官方第三方行程接口，请使用分享文本 / 截图识别后人工确认");
    }
}
