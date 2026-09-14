package com.pinhaoche.admin;

import com.pinhaoche.ride.RideService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminRideSuppressionServiceTest {

    @Test
    void firstDeleteVerifiesRideThenPersistsSuppressionAudit() {
        var repository = mock(AdminRideSuppressionRepository.class);
        var rides = mock(RideService.class);
        when(repository.findById(12L)).thenReturn(Optional.empty());
        var service = new AdminRideSuppressionService(repository, rides);

        var result = service.suppress(12L);

        assertFalse(result.alreadySuppressed());
        verify(rides).get(12L);
        verify(repository).save(any(AdminRideSuppression.class));
    }

    @Test
    void repeatedDeleteKeepsOriginalAuditAndDoesNotWriteAgain() {
        var repository = mock(AdminRideSuppressionRepository.class);
        var rides = mock(RideService.class);
        var at = OffsetDateTime.of(2026, 9, 14, 1, 2, 3, 0, ZoneOffset.UTC);
        when(repository.findById(12L)).thenReturn(Optional.of(new AdminRideSuppression(12L, at)));
        var service = new AdminRideSuppressionService(repository, rides);

        var result = service.suppress(12L);

        assertTrue(result.alreadySuppressed());
        org.junit.jupiter.api.Assertions.assertEquals(at, result.deletedAt());
        verify(rides).get(12L);
        verify(repository, never()).save(any());
    }
}
