package com.pinhaoche.place;

import com.pinhaoche.common.api.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 预设地点服务（规范 §2.2 / Agent A 边界）：
 * - 管理员新增/修改后小程序无需发版即可显示 → 走数据库 + Redis 缓存，管理端变更后主动失效。
 * - 禁止加入模糊搜索系统：只有精确列表，客户端全量拉取。
 */
@Service
public class PlaceService {

    /** 缓存键独立于其它模块，失效策略：管理员写操作后删除（Cache-Aside + 写后失效）。 */
    private static final String CACHE_KEY = "place:enabled:list";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final PlaceRepository repository;
    private final StringRedisTemplate redis;

    public PlaceService(PlaceRepository repository, StringRedisTemplate redis) {
        this.repository = repository;
        this.redis = redis;
    }

    public record PlaceDto(long id, String name, int sortOrder) {
    }

    @Transactional(readOnly = true)
    public List<PlaceDto> listEnabled() {
        // 缓存只承担"挡读流量"职责，内容以 MySQL 为准；命中失败静默回源，不影响可用性
        try {
            String cached = redis.opsForValue().get(CACHE_KEY);
            if (cached != null && !cached.isBlank()) {
                return deserialize(cached);
            }
        } catch (RuntimeException ignored) {
            // Redis 不可用时直接回源，地点列表是小数据量查询，可接受
        }
        List<PlaceDto> result = repository.findByEnabledTrueOrderBySortOrderAscIdAsc().stream()
                .map(p -> new PlaceDto(p.getId(), p.getName(), p.getSortOrder()))
                .toList();
        try {
            redis.opsForValue().set(CACHE_KEY, serialize(result), CACHE_TTL);
        } catch (RuntimeException ignored) {
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<PlaceDto> listAll() {
        return repository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(p -> new PlaceDto(p.getId(), p.getName(), p.getSortOrder()))
                .toList();
    }

    /** 供 ride 模块校验 PRESET 地点引用并取得快照名称（模块边界：application service，不暴露 repository）。 */
    @Transactional(readOnly = true)
    public java.util.Optional<String> nameOf(long placeId) {
        return repository.findById(placeId)
                .filter(Place::getEnabled)
                .map(Place::getName);
    }

    @Transactional
    public PlaceDto create(String name, int sortOrder) {
        if (repository.findAllByOrderBySortOrderAscIdAsc().stream().anyMatch(p -> p.getName().equals(name))) {
            throw ApiException.validation("地点已存在: " + name);
        }
        Place saved = repository.save(new Place(name, sortOrder, OffsetDateTime.now()));
        evictCache();
        return new PlaceDto(saved.getId(), saved.getName(), saved.getSortOrder());
    }

    @Transactional
    public PlaceDto rename(long id, String name) {
        Place p = mustFind(id);
        p.setName(name);
        p.setUpdatedAt(OffsetDateTime.now());
        evictCache();
        return new PlaceDto(p.getId(), p.getName(), p.getSortOrder());
    }

    @Transactional
    public void reorder(List<Long> orderedIds) {
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 0; i < orderedIds.size(); i++) {
            Place p = mustFind(orderedIds.get(i));
            p.setSortOrder(i);
            p.setUpdatedAt(now);
        }
        evictCache();
    }

    @Transactional
    public void setEnabled(long id, boolean enabled) {
        Place p = mustFind(id);
        p.setEnabled(enabled);
        p.setUpdatedAt(OffsetDateTime.now());
        evictCache();
    }

    private Place mustFind(long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("地点不存在"));
    }

    private void evictCache() {
        try {
            redis.delete(CACHE_KEY);
        } catch (RuntimeException ignored) {
            // 失效失败时靠 TTL 兜底，最多 10 分钟不一致，对地点列表可接受
        }
    }

    private String serialize(List<PlaceDto> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            PlaceDto p = list.get(i);
            sb.append(i > 0 ? "," : "").append(p.id()).append('|').append(p.name()).append('|').append(p.sortOrder());
        }
        return sb.append(']').toString();
    }

    private List<PlaceDto> deserialize(String raw) {
        String body = raw.substring(1, raw.length() - 1);
        if (body.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(body.split(",", -1))
                .map(s -> s.split("\\|", -1))
                .map(a -> new PlaceDto(Long.parseLong(a[0]), a[1], Integer.parseInt(a[2])))
                .toList();
    }
}
