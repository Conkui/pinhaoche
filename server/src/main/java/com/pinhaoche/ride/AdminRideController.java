package com.pinhaoche.ride;

import com.pinhaoche.common.api.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端拼车单只读列表（RFC-009）：放 ride 模块内（先例：place/AdminPlaceController），
 * /api/v1/admin/** 已被 WebConfig 的 X-Admin-Key 拦截器保护，这里不做额外鉴权。
 */
@RestController
@RequestMapping("/api/v1/admin/rides")
public class AdminRideController {

    /** 分页 size 上限：超过按 100 截断（与 feedback 列表口径一致）。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final RideService rideService;

    public AdminRideController(RideService rideService) {
        this.rideService = rideService;
    }

    /** 与 FeedbackService.FeedbackPage 同构的分页壳（各模块自带 DTO，避免跨模块共享类型）。 */
    public record RidePage(long total, int page, int size, List<RideService.RideDto> items) {
    }

    /**
     * @param status "ALL" 或 RideStatus 枚举名；非法值返回 400（ApiException.validation）。
     *               为什么不直接 valueOf 后吞异常——管理端误传参数必须显式失败，静默降级成"全部"会误导运营判断。
     */
    @GetMapping
    public RidePage list(@RequestParam(defaultValue = "ALL") String status,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size) {
        RideStatus filter = parseStatus(status);
        int p = Math.max(page, 0);
        int s = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        // 管理端列表按创建时间倒序；追加 id 倒序兜底，同一秒内创建的单排序稳定（分页翻页不跳条）
        var pageable = PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        var result = rideService.pageAll(filter, pageable);
        return new RidePage(result.getTotalElements(), p, s, result.getContent());
    }

    private RideStatus parseStatus(String status) {
        if (status == null || "ALL".equals(status)) {
            return null;
        }
        try {
            return RideStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("status 必须为 ALL 或 RideStatus 枚举名");
        }
    }
}
