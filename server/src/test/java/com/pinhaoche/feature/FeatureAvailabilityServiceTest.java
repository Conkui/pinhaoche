package com.pinhaoche.feature;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.api.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeatureAvailabilityServiceTest {

    @Test
    void disabledSettingBlocksRealtimeWritesWithAnnouncement() {
        RealtimeCarpoolSettingRepository repository = mock(RealtimeCarpoolSettingRepository.class);
        FindFriendsSettingRepository findFriendsRepository = mock(FindFriendsSettingRepository.class);
        when(repository.findById((byte) 1)).thenReturn(Optional.of(
                new RealtimeCarpoolSetting(false, "系统维护，请稍后再试", OffsetDateTime.now(ZoneOffset.UTC))));
        FeatureAvailabilityService service = new FeatureAvailabilityService(repository, findFriendsRepository);

        ApiException error = assertThrows(ApiException.class, service::requireRealtimeCarpoolEnabled);

        assertEquals(ErrorCode.FORBIDDEN, error.getCode());
        assertEquals("系统维护，请稍后再试", error.getMessage());
    }

    @Test
    void disabledSettingRequiresAnnouncement() {
        RealtimeCarpoolSettingRepository repository = mock(RealtimeCarpoolSettingRepository.class);
        FindFriendsSettingRepository findFriendsRepository = mock(FindFriendsSettingRepository.class);
        when(repository.findById((byte) 1)).thenReturn(Optional.of(
                new RealtimeCarpoolSetting(true, "旧公告", OffsetDateTime.now(ZoneOffset.UTC))));
        FeatureAvailabilityService service = new FeatureAvailabilityService(repository, findFriendsRepository);

        ApiException error = assertThrows(ApiException.class,
                () -> service.updateRealtimeCarpool(false, "  "));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.getCode());
    }

    @Test
    void updateReturnsDisabledAvailabilityAndSanitizedAnnouncement() {
        RealtimeCarpoolSettingRepository repository = mock(RealtimeCarpoolSettingRepository.class);
        FindFriendsSettingRepository findFriendsRepository = mock(FindFriendsSettingRepository.class);
        RealtimeCarpoolSetting setting = new RealtimeCarpoolSetting(true, "旧公告", OffsetDateTime.now(ZoneOffset.UTC));
        when(repository.findById((byte) 1)).thenReturn(Optional.of(setting));
        when(repository.save(any(RealtimeCarpoolSetting.class))).thenAnswer(invocation -> invocation.getArgument(0));
        FeatureAvailabilityService service = new FeatureAvailabilityService(repository, findFriendsRepository);

        var result = service.updateRealtimeCarpool(false, "\n 维护中\u0000 ");

        assertFalse(result.enabled());
        assertEquals("维护中", result.announcement());
    }

    @Test
    void disabledFindFriendsBlocksOnlyPrebookGateWithItsOwnAnnouncement() {
        RealtimeCarpoolSettingRepository realtimeRepository = mock(RealtimeCarpoolSettingRepository.class);
        FindFriendsSettingRepository findFriendsRepository = mock(FindFriendsSettingRepository.class);
        when(findFriendsRepository.findById((byte) 1)).thenReturn(Optional.of(
                new FindFriendsSetting(false, "找拼友维护中", OffsetDateTime.now(ZoneOffset.UTC))));
        FeatureAvailabilityService service = new FeatureAvailabilityService(realtimeRepository, findFriendsRepository);

        ApiException error = assertThrows(ApiException.class, service::requireFindFriendsEnabled);

        assertEquals(ErrorCode.FORBIDDEN, error.getCode());
        assertEquals("找拼友维护中", error.getMessage());
    }

    @Test
    void updateFindFriendsDoesNotWriteRealtimeSetting() {
        RealtimeCarpoolSettingRepository realtimeRepository = mock(RealtimeCarpoolSettingRepository.class);
        FindFriendsSettingRepository findFriendsRepository = mock(FindFriendsSettingRepository.class);
        FindFriendsSetting setting = new FindFriendsSetting(true, "旧公告", OffsetDateTime.now(ZoneOffset.UTC));
        when(findFriendsRepository.findById((byte) 1)).thenReturn(Optional.of(setting));
        when(findFriendsRepository.save(any(FindFriendsSetting.class))).thenAnswer(invocation -> invocation.getArgument(0));
        FeatureAvailabilityService service = new FeatureAvailabilityService(realtimeRepository, findFriendsRepository);

        var result = service.updateFindFriends(false, "找拼友暂停");

        assertFalse(result.enabled());
        assertEquals("找拼友暂停", result.announcement());
    }

    @Test
    void missingRowsUseDifferentAnnouncementsForTheTwoFeatures() {
        RealtimeCarpoolSettingRepository realtimeRepository = mock(RealtimeCarpoolSettingRepository.class);
        FindFriendsSettingRepository findFriendsRepository = mock(FindFriendsSettingRepository.class);
        when(realtimeRepository.findById((byte) 1)).thenReturn(Optional.empty());
        when(findFriendsRepository.findById((byte) 1)).thenReturn(Optional.empty());
        FeatureAvailabilityService service = new FeatureAvailabilityService(realtimeRepository, findFriendsRepository);

        assertEquals(FeatureAvailabilityService.DEFAULT_REALTIME_ANNOUNCEMENT,
                service.readRealtimeCarpool().announcement());
        assertEquals(FeatureAvailabilityService.DEFAULT_FIND_FRIENDS_ANNOUNCEMENT,
                service.readFindFriends().announcement());
    }
}
