package com.pinhaoche.admin;

import com.pinhaoche.common.api.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /** 单次最多 100 个，防止后台误操作变成无界数据清理任务。 */
    public record BulkSuppressRequest(@NotEmpty @Size(max = 100) List<@NotNull Long> rideIds) {
    }

    /**
     * 批量隐藏测试订单；仍由 WebConfig 对整个 admin 路径强制校验 X-Admin-Key。
     * 这里不接受“按条件全删”，调用方必须明确提交订单 ID，避免误隐藏生产数据。
     */
    @PostMapping("/bulk-suppress")
    public AdminRideSuppressionService.BulkSuppressionResult bulkSuppress(
            @Valid @RequestBody BulkSuppressRequest request) {
        try {
            return suppressionService.suppressAll(request.rideIds());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(e.getMessage());
        }
    }
}
