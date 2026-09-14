package com.pinhaoche.admin;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端删除入口；X-Admin-Key 鉴权由 WebConfig 覆盖整个 /api/v1/admin/** 路径。 */
@RestController
@RequestMapping("/api/v1/admin/rides")
public class AdminRideSuppressionController {
    private final AdminRideSuppressionService suppressionService;

    public AdminRideSuppressionController(AdminRideSuppressionService suppressionService) {
        this.suppressionService = suppressionService;
    }

    @DeleteMapping("/{rideId}")
    public AdminRideSuppressionService.SuppressionResult delete(@PathVariable long rideId) {
        return suppressionService.suppress(rideId);
    }
}
