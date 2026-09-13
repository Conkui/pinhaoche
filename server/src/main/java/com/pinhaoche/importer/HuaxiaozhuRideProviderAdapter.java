package com.pinhaoche.importer;

import org.springframework.stereotype.Component;

/** 花小猪 Adapter，降级策略同 Didi（规范 §17）。 */
@Component
public class HuaxiaozhuRideProviderAdapter implements RideProviderAdapter {

    @Override
    public boolean supports(ImportContext context) {
        return "HUAXIAOZHU".equals(context.provider());
    }

    @Override
    public RideImportResult importRide(ImportContext context) {
        return RideImportResult.failed("花小猪无官方第三方行程接口，请使用分享文本 / 截图识别后人工确认");
    }
}
