package com.pinhaoche.place;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端地点 CRUD（RFC-005）：X-Admin-Key 保护（见 WebConfig）。
 * 停用用 DELETE 语义（软删：enabled=false），因为 place 可能已被历史 Ride 引用，不能物理删除。
 */
@RestController
@RequestMapping("/api/v1/admin/places")
public class AdminPlaceController {

    private final PlaceService placeService;

    public AdminPlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    public record UpsertRequest(String name, Integer sortOrder) {
    }

    public record ReorderRequest(List<Long> orderedIds) {
    }

    @GetMapping
    public List<PlaceService.PlaceDto> listAll() {
        return placeService.listAll();
    }

    @PostMapping
    public PlaceService.PlaceDto create(@RequestBody UpsertRequest request) {
        return placeService.create(request.name(), request.sortOrder() == null ? 0 : request.sortOrder());
    }

    @PutMapping("/{id}")
    public PlaceService.PlaceDto rename(@PathVariable long id, @RequestBody UpsertRequest request) {
        return placeService.rename(id, request.name());
    }

    @PutMapping("/reorder")
    public void reorder(@RequestBody ReorderRequest request) {
        placeService.reorder(request.orderedIds());
    }

    @PostMapping("/{id}/enable")
    public void enable(@PathVariable long id) {
        placeService.setEnabled(id, true);
    }

    /** 停用（软删）。 */
    @DeleteMapping("/{id}")
    public void disable(@PathVariable long id) {
        placeService.setEnabled(id, false);
    }
}
