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

/** 管理端物理删除入口；X-Admin-Key 鉴权由 WebConfig 覆盖整个 /api/v1/admin/** 路径。 */
@RestController
@RequestMapping("/api/v1/admin/rides")
public class AdminRideDeletionController {
    private final AdminRideDeletionService deletionService;

    public AdminRideDeletionController(AdminRideDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @DeleteMapping("/{rideId}")
    public AdminRideDeletionService.DeletionResult delete(@PathVariable long rideId) {
        return deletionService.delete(rideId);
    }

    /** 单次最多 100 个，防止后台误操作变成无界数据清理任务。 */
    public record BulkDeleteRequest(@NotEmpty @Size(max = 100) List<@NotNull Long> rideIds) {
    }

    /**
     * 保留既有路径，以便已经部署的后台静态页升级后仍可调用；语义已改为物理删除。
     * 只接受明确勾选的订单 ID，绝不提供“按条件全删”，降低生产误删风险。
     */
    @PostMapping({"/bulk-delete", "/bulk-suppress"})
    public AdminRideDeletionService.BulkDeletionResult bulkDelete(
            @Valid @RequestBody BulkDeleteRequest request) {
        try {
            return deletionService.deleteAll(request.rideIds());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(e.getMessage());
        }
    }
}
