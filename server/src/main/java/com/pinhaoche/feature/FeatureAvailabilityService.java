package com.pinhaoche.feature;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.util.TextSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 运营功能可用性应用服务。
 * Ride/Chat 等模块只能调用本服务，不得读取配置表；这样临时运营停用能在所有写路径复用同一判断。
 */
@Service
public class FeatureAvailabilityService {

    static final String DEFAULT_ANNOUNCEMENT = "实时拼功能暂未开启，请留意后续公告。";
    static final int ANNOUNCEMENT_MAX = 500;
    private static final byte SINGLETON_ID = 1;

    private final RealtimeCarpoolSettingRepository realtimeRepository;
    private final FindFriendsSettingRepository findFriendsRepository;

    public FeatureAvailabilityService(RealtimeCarpoolSettingRepository realtimeRepository,
                                      FindFriendsSettingRepository findFriendsRepository) {
        this.realtimeRepository = realtimeRepository;
        this.findFriendsRepository = findFriendsRepository;
    }

    public record RealtimeCarpoolAvailability(boolean enabled, String announcement, OffsetDateTime updatedAt) {
    }

    /** 给小程序读取：开启时也返回公告，关闭时可无需二次请求直接展示。 */
    @Transactional(readOnly = true)
    public RealtimeCarpoolAvailability readRealtimeCarpool() {
        return toDto(realtimeSetting());
    }

    /** “找拼友”是预打车的独立运营开关，不能与“我已打上车”的实时拼混用。 */
    @Transactional(readOnly = true)
    public RealtimeCarpoolAvailability readFindFriends() {
        return toDto(findFriendsSetting());
    }

    /**
     * 供 Ride/Chat 写操作调用的统一门禁。使用既有 FORBIDDEN，避免未经冻结契约新增错误码；
     * 信息带公告，调用端可直接向用户解释为何暂不可发布、加入或建立拼友会话。
     */
    @Transactional(readOnly = true)
    public void requireRealtimeCarpoolEnabled() {
        RealtimeCarpoolSetting setting = realtimeSetting();
        if (!setting.isEnabled()) {
            throw ApiException.forbidden(setting.getAnnouncement());
        }
    }

    /** 供预打车列表、发布和加入路径调用的独立门禁。 */
    @Transactional(readOnly = true)
    public void requireFindFriendsEnabled() {
        FindFriendsSetting setting = findFriendsSetting();
        if (!setting.isEnabled()) {
            throw ApiException.forbidden(setting.getAnnouncement());
        }
    }

    /** 管理端更新。关闭时公告必须非空，防止用户只看到无解释的不可用页面。 */
    @Transactional
    public RealtimeCarpoolAvailability updateRealtimeCarpool(boolean enabled, String announcement) {
        String cleaned = cleanAnnouncement(enabled, announcement, "实时拼");
        RealtimeCarpoolSetting setting = realtimeSetting();
        setting.update(enabled, cleaned, now());
        return toDto(realtimeRepository.save(setting));
    }

    /** 管理端更新找拼友开关；公告仅影响找拼友公告页。 */
    @Transactional
    public RealtimeCarpoolAvailability updateFindFriends(boolean enabled, String announcement) {
        String cleaned = cleanAnnouncement(enabled, announcement, "找拼友");
        FindFriendsSetting setting = findFriendsSetting();
        setting.update(enabled, cleaned, now());
        return toDto(findFriendsRepository.save(setting));
    }

    private static String cleanAnnouncement(boolean enabled, String announcement, String featureName) {
        String cleaned = TextSanitizer.clean(announcement);
        if (cleaned == null || cleaned.isBlank()) {
            if (!enabled) {
                throw ApiException.validation("停用%s时必须填写公告".formatted(featureName));
            }
            return DEFAULT_ANNOUNCEMENT;
        }
        if (cleaned.length() > ANNOUNCEMENT_MAX) {
            throw ApiException.validation("公告最长 %d 个字符".formatted(ANNOUNCEMENT_MAX));
        }
        return cleaned;
    }

    private RealtimeCarpoolSetting realtimeSetting() {
        // 迁移会预置唯一行；仅为手工清库/旧环境提供安全默认值。这里不落库，
        // 因为 readRealtimeCarpool 是只读事务；下一次管理端保存会把该默认值持久化。
        return realtimeRepository.findById(SINGLETON_ID)
                .orElseGet(() -> new RealtimeCarpoolSetting(true, DEFAULT_ANNOUNCEMENT, now()));
    }

    private FindFriendsSetting findFriendsSetting() {
        return findFriendsRepository.findById(SINGLETON_ID)
                .orElseGet(() -> new FindFriendsSetting(true, DEFAULT_ANNOUNCEMENT, now()));
    }

    private static RealtimeCarpoolAvailability toDto(RealtimeCarpoolSetting setting) {
        return new RealtimeCarpoolAvailability(setting.isEnabled(), setting.getAnnouncement(), setting.getUpdatedAt());
    }

    private static RealtimeCarpoolAvailability toDto(FindFriendsSetting setting) {
        return new RealtimeCarpoolAvailability(setting.isEnabled(), setting.getAnnouncement(), setting.getUpdatedAt());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
