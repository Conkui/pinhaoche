package com.pinhaoche.admin;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.feature.FeatureAvailabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端运营开关；/api/v1/admin/** 已由 WebConfig 强制校验 X-Admin-Key。 */
@RestController
@RequestMapping("/api/v1/admin/features")
public class AdminFeatureAvailabilityController {

    private final FeatureAvailabilityService featureAvailabilityService;

    public AdminFeatureAvailabilityController(FeatureAvailabilityService featureAvailabilityService) {
        this.featureAvailabilityService = featureAvailabilityService;
    }

    public record UpdateRequest(Boolean enabled, String announcement) {
    }

    @GetMapping("/realtime-carpool")
    public FeatureAvailabilityService.RealtimeCarpoolAvailability read() {
        return featureAvailabilityService.readRealtimeCarpool();
    }

    @PutMapping("/realtime-carpool")
    public FeatureAvailabilityService.RealtimeCarpoolAvailability update(@RequestBody UpdateRequest request) {
        if (request == null || request.enabled() == null) {
            throw ApiException.validation("enabled 必须为 true 或 false");
        }
        return featureAvailabilityService.updateRealtimeCarpool(request.enabled(), request.announcement());
    }

    @GetMapping("/find-friends")
    public FeatureAvailabilityService.RealtimeCarpoolAvailability readFindFriends() {
        return featureAvailabilityService.readFindFriends();
    }

    @PutMapping("/find-friends")
    public FeatureAvailabilityService.RealtimeCarpoolAvailability updateFindFriends(@RequestBody UpdateRequest request) {
        if (request == null || request.enabled() == null) {
            throw ApiException.validation("enabled 必须为 true 或 false");
        }
        return featureAvailabilityService.updateFindFriends(request.enabled(), request.announcement());
    }
}
