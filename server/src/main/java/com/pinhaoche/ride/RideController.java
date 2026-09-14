package com.pinhaoche.ride;

import com.pinhaoche.common.auth.AuthInterceptor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Ride 接口（openapi: /rides, /rides/{rideId}, /rides/{rideId}/join, /rides/{rideId}/transitions）。 */
@RestController
@RequestMapping("/api/v1")
public class RideController {

    private final RideService rideService;

    public RideController(RideService rideService) {
        this.rideService = rideService;
    }

    @GetMapping("/rides")
    public List<RideService.RideDto> list(@RequestParam String view) {
        return rideService.list(view);
    }

    @GetMapping("/rides/mine")
    public List<RideService.RideDto> listMine(jakarta.servlet.http.HttpServletRequest http) {
        return rideService.listMine(userId(http));
    }

    @GetMapping("/rides/{rideId}")
    public RideService.RideDto get(@PathVariable long rideId, jakarta.servlet.http.HttpServletRequest http) {
        return rideService.getForViewer(rideId, userId(http));
    }

    /** RFC-012：联系人是 owner-only 附属资源，不能混入冻结 Ride 响应。 */
    @GetMapping("/rides/{rideId}/member-contacts")
    public List<RideService.MemberContactDto> memberContacts(@PathVariable long rideId,
                                                              jakarta.servlet.http.HttpServletRequest http) {
        return rideService.listMemberContacts(rideId, userId(http));
    }

    @PostMapping("/rides")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED) // openapi 契约：201 Created
    public RideService.RideDto create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RideService.CreateRideRequest request,
            jakarta.servlet.http.HttpServletRequest http) {
        long userId = userId(http);
        return rideService.create(userId, request, idempotencyKey);
    }

    @PostMapping("/rides/{rideId}/join")
    public RideService.RideDto join(
            @PathVariable long rideId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            jakarta.servlet.http.HttpServletRequest http) {
        return rideService.join(rideId, userId(http), idempotencyKey);
    }

    @PostMapping("/rides/{rideId}/transitions")
    public RideService.RideDto transit(
            @PathVariable long rideId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RideService.TransitionRequest body,
            jakarta.servlet.http.HttpServletRequest http) {
        return rideService.transit(rideId, userId(http), body.targetStatus(), body.version(), idempotencyKey);
    }

    private long userId(jakarta.servlet.http.HttpServletRequest http) {
        Object v = http.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (v == null) {
            throw com.pinhaoche.common.api.ApiException.unauthorized("未登录");
        }
        return (Long) v;
    }
}
