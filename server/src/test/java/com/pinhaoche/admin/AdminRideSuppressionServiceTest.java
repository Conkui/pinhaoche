package com.pinhaoche.admin;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminRideSuppressionServiceTest {

    @Test
    void firstDeletePersistsSuppressionAudit() {
        var repository = mock(AdminRideSuppressionRepository.class);
        when(repository.findById(12L)).thenReturn(Optional.empty());
        var service = new AdminRideSuppressionService(repository);

        var result = service.suppress(12L);

        assertFalse(result.alreadySuppressed());
        verify(repository).save(any(AdminRideSuppression.class));
    }

    @Test
    void repeatedDeleteKeepsOriginalAuditAndDoesNotWriteAgain() {
        var repository = mock(AdminRideSuppressionRepository.class);
        var at = OffsetDateTime.of(2026, 9, 14, 1, 2, 3, 0, ZoneOffset.UTC);
        when(repository.findById(12L)).thenReturn(Optional.of(new AdminRideSuppression(12L, at)));
        var service = new AdminRideSuppressionService(repository);

        var result = service.suppress(12L);

        assertTrue(result.alreadySuppressed());
        org.junit.jupiter.api.Assertions.assertEquals(at, result.deletedAt());
        verify(repository, never()).save(any());
    }

    @Test
    void bulkSuppressDeduplicatesIdsAndSeparatesNewFromExistingAudits() {
        var repository = mock(AdminRideSuppressionRepository.class);
        var at = OffsetDateTime.of(2026, 9, 14, 1, 2, 3, 0, ZoneOffset.UTC);
        when(repository.findAllById(org.mockito.ArgumentMatchers.<Long>anyIterable()))
                .thenReturn(List.of(new AdminRideSuppression(12L, at)));
        var service = new AdminRideSuppressionService(repository);

        var result = service.suppressAll(List.of(12L, 34L, 12L));

        org.junit.jupiter.api.Assertions.assertEquals(2, result.requested());
        org.junit.jupiter.api.Assertions.assertEquals(1, result.newlySuppressed());
        org.junit.jupiter.api.Assertions.assertEquals(1, result.alreadySuppressed());
        verify(repository).saveAll(org.mockito.ArgumentMatchers.argThat(items -> {
            // CrudRepository 的批量参数契约是 Iterable，不能假定实现为 List。
            var iterator = items.iterator();
            return iterator.hasNext()
                    && iterator.next().getRideId() == 34L
                    && !iterator.hasNext();
        }));
    }

    @Test
    void bulkSuppressRejectsNonPositiveIdsBeforeReadingOrWritingAudits() {
        var repository = mock(AdminRideSuppressionRepository.class);
        var service = new AdminRideSuppressionService(repository);

        assertThrows(IllegalArgumentException.class, () -> service.suppressAll(List.of(12L, 0L)));

        verify(repository, never()).findAllById(org.mockito.ArgumentMatchers.<Long>anyIterable());
        verify(repository, never()).saveAll(any());
    }
}
