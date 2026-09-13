package com.pinhaoche.importer;

import com.pinhaoche.common.auth.AuthInterceptor;
import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.ride.RideService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 导入接口（openapi: POST /rides/import，RFC-004）。 */
@RestController
@RequestMapping("/api/v1")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/rides/import")
    public RideService.RideDto importRide(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                          @RequestBody ImportService.ImportRideRequest request,
                                          HttpServletRequest http) {
        Object v = http.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (v == null) {
            throw ApiException.unauthorized("未登录");
        }
        return importService.importRide((Long) v, request, idempotencyKey);
    }
}
