package com.pinhaoche.feature;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 小程序实时拼入口读取开关；仍经全局 token 鉴权，避免将运营公告接口裸露在公网。 */
@RestController
@RequestMapping("/api/v1/features")
public class FeatureAvailabilityController {

    private final FeatureAvailabilityService featureAvailabilityService;

    public FeatureAvailabilityController(FeatureAvailabilityService featureAvailabilityService) {
        this.featureAvailabilityService = featureAvailabilityService;
    }

    @GetMapping("/realtime-carpool")
    public FeatureAvailabilityService.RealtimeCarpoolAvailability realtimeCarpool() {
        return featureAvailabilityService.readRealtimeCarpool();
    }
}
