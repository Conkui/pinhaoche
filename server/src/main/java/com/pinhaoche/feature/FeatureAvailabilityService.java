package com.pinhaoche.feature;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.util.TextSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 实时拼可用性应用服务。
 * Ride/Chat 等模块只能调用本服务，不得读取配置表；这样临时运营停用能在所有写路径复用同一判断。
 */
@Service
public class FeatureAvailabilityService {

    static final String DEFAULT_ANNOUNCEMENT = "实时拼功能暂未开启，请留意后续公告。";
    static final int ANNOUNCEMENT_MAX = 500;
    private static final byte SINGLETON_ID = 1;

    private final RealtimeCarpoolSettingRepository repository;

    public FeatureAvailabilityService(RealtimeCarpoolSettingRepository repository) {
        this.repository = repository;
    }

    public record RealtimeCarpoolAvailability(boolean enabled, String announcement, OffsetDateTime updatedAt) {
    }

    /** 给小程序读取：开启时也返回公告，关闭时可无需二次请求直接展示。 */
    @Transactional(readOnly = true)
    public RealtimeCarpoolAvailability readRealtimeCarpool() {
        return toDto(setting());
    }

    /**
     * 供 Ride/Chat 写操作调用的统一门禁。使用既有 FORBIDDEN，避免未经冻结契约新增错误码；
     * 信息带公告，调用端可直接向用户解释为何暂不可发布、加入或建立拼友会话。
     */
    @Transactional(readOnly = true)
    public void requireRealtimeCarpoolEnabled() {
        RealtimeCarpoolSetting setting = setting();
        if (!setting.isEnabled()) {
            throw ApiException.forbidden(setting.getAnnouncement());
        }
    }

    /** 管理端更新。关闭时公告必须非空，防止用户只看到无解释的不可用页面。 */
    @Transactional
    public RealtimeCarpoolAvailability updateRealtimeCarpool(boolean enabled, String announcement) {
        String cleaned = TextSanitizer.clean(announcement);
        if (cleaned == null || cleaned.isBlank()) {
            if (!enabled) {
                throw ApiException.validation("停用实时拼时必须填写公告");
            }
            cleaned = DEFAULT_ANNOUNCEMENT;
        }
        if (cleaned.length() > ANNOUNCEMENT_MAX) {
            throw ApiException.validation("公告最长 %d 个字符".formatted(ANNOUNCEMENT_MAX));
        }
        RealtimeCarpoolSetting setting = setting();
        setting.update(enabled, cleaned, now());
        return toDto(repository.save(setting));
    }

    private RealtimeCarpoolSetting setting() {
        // 迁移会预置唯一行；仅为手工清库/旧环境提供安全默认值。这里不落库，
        // 因为 readRealtimeCarpool 是只读事务；下一次管理端保存会把该默认值持久化。
        return repository.findById(SINGLETON_ID)
                .orElseGet(() -> new RealtimeCarpoolSetting(true, DEFAULT_ANNOUNCEMENT, now()));
    }

    private static RealtimeCarpoolAvailability toDto(RealtimeCarpoolSetting setting) {
        return new RealtimeCarpoolAvailability(setting.isEnabled(), setting.getAnnouncement(), setting.getUpdatedAt());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
