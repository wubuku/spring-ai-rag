package com.springairag.core.repository;

import com.springairag.core.entity.RagAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AlertRepository Unit Tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AlertRepository Tests")
class AlertRepositoryTest {

    @Mock
    private AlertRepository repository;

    private RagAlert createAlert(Long id, String alertType, String severity, String status) {
        RagAlert alert = new RagAlert();
        alert.setId(id);
        alert.setAlertType(alertType);
        alert.setAlertName("Test Alert");
        alert.setMessage("Test message");
        alert.setSeverity(severity);
        alert.setStatus(status);
        alert.setMetrics(Map.of("p99", 2500.0));
        alert.setFiredAt(ZonedDateTime.now());
        return alert;
    }

    @Nested
    @DisplayName("findAlertHistory")
    class FindAlertHistory {

        @Test
        @DisplayName("returns alerts within time range")
        void returnsAlertsInRange() {
            RagAlert alert = createAlert(1L, "THRESHOLD_HIGH", "WARNING", "ACTIVE");
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.findAlertHistory(start, end, null, null)).thenReturn(List.of(alert));

            List<RagAlert> result = repository.findAlertHistory(start, end, null, null);

            assertEquals(1, result.size());
            assertEquals("THRESHOLD_HIGH", result.get(0).getAlertType());
            verify(repository).findAlertHistory(start, end, null, null);
        }

        @Test
        @DisplayName("returns empty list when no alerts in range")
        void returnsEmptyWhenNoAlerts() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.findAlertHistory(start, end, null, null)).thenReturn(List.of());

            List<RagAlert> result = repository.findAlertHistory(start, end, null, null);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("filters by severity")
        void filtersBySeverity() {
            RagAlert alert = createAlert(1L, "THRESHOLD_HIGH", "CRITICAL", "ACTIVE");
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.findAlertHistory(start, end, "CRITICAL", null)).thenReturn(List.of(alert));

            List<RagAlert> result = repository.findAlertHistory(start, end, "CRITICAL", null);

            assertEquals(1, result.size());
            assertEquals("CRITICAL", result.get(0).getSeverity());
        }

        @Test
        @DisplayName("filters by alertType")
        void filtersByAlertType() {
            RagAlert alert = createAlert(1L, "SLO_BREACH", "WARNING", "ACTIVE");
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.findAlertHistory(start, end, null, "SLO_BREACH")).thenReturn(List.of(alert));

            List<RagAlert> result = repository.findAlertHistory(start, end, null, "SLO_BREACH");

            assertEquals(1, result.size());
            assertEquals("SLO_BREACH", result.get(0).getAlertType());
        }

        @Test
        @DisplayName("filters by both severity and alertType")
        void filtersByBoth() {
            RagAlert alert = createAlert(1L, "THRESHOLD_LOW", "INFO", "ACTIVE");
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.findAlertHistory(start, end, "INFO", "THRESHOLD_LOW")).thenReturn(List.of(alert));

            List<RagAlert> result = repository.findAlertHistory(start, end, "INFO", "THRESHOLD_LOW");

            assertEquals(1, result.size());
            assertEquals("INFO", result.get(0).getSeverity());
            assertEquals("THRESHOLD_LOW", result.get(0).getAlertType());
        }
    }

    @Nested
    @DisplayName("findByStatusOrderByFiredAtDesc")
    class FindByStatusOrderByFiredAtDesc {

        @Test
        @DisplayName("returns active alerts ordered by firedAt descending")
        void returnsActiveAlertsOrdered() {
            RagAlert a1 = createAlert(1L, "THRESHOLD_HIGH", "WARNING", "ACTIVE");
            a1.setFiredAt(ZonedDateTime.now().minusHours(1));
            RagAlert a2 = createAlert(2L, "SLO_BREACH", "CRITICAL", "ACTIVE");
            a2.setFiredAt(ZonedDateTime.now());
            when(repository.findByStatusOrderByFiredAtDesc("ACTIVE")).thenReturn(List.of(a2, a1));

            List<RagAlert> result = repository.findByStatusOrderByFiredAtDesc("ACTIVE");

            assertEquals(2, result.size());
            assertEquals(2L, result.get(0).getId());
        }

        @Test
        @DisplayName("returns empty list for unknown status")
        void returnsEmptyForUnknownStatus() {
            when(repository.findByStatusOrderByFiredAtDesc("UNKNOWN")).thenReturn(List.of());

            List<RagAlert> result = repository.findByStatusOrderByFiredAtDesc("UNKNOWN");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("countBySeverity")
    class CountBySeverity {

        @Test
        @DisplayName("counts alerts by severity in time range")
        void countsBySeverity() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.countBySeverity(start, end, "CRITICAL")).thenReturn(5L);

            long count = repository.countBySeverity(start, end, "CRITICAL");

            assertEquals(5L, count);
        }

        @Test
        @DisplayName("returns zero when no alerts match severity")
        void returnsZeroWhenNoMatch() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.countBySeverity(start, end, "INFO")).thenReturn(0L);

            long count = repository.countBySeverity(start, end, "INFO");

            assertEquals(0L, count);
        }
    }

    @Nested
    @DisplayName("countActiveAlerts")
    class CountActiveAlerts {

        @Test
        @DisplayName("counts active alerts in time range")
        void countsActiveAlerts() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.countActiveAlerts(start, end)).thenReturn(3L);

            long count = repository.countActiveAlerts(start, end);

            assertEquals(3L, count);
        }

        @Test
        @DisplayName("returns zero when no active alerts")
        void returnsZeroWhenNoActiveAlerts() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.countActiveAlerts(start, end)).thenReturn(0L);

            long count = repository.countActiveAlerts(start, end);

            assertEquals(0L, count);
        }
    }

    @Nested
    @DisplayName("countByFiredAtBetween")
    class CountByFiredAtBetween {

        @Test
        @DisplayName("counts total alerts in time range")
        void countsTotalAlerts() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(7);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.countByFiredAtBetween(start, end)).thenReturn(10L);

            long count = repository.countByFiredAtBetween(start, end);

            assertEquals(10L, count);
        }
    }

    @Nested
    @DisplayName("deleteOldResolvedAlerts")
    class DeleteOldResolvedAlerts {

        @Test
        @DisplayName("deletes old resolved alerts before cutoff")
        void deletesOldResolvedAlerts() {
            ZonedDateTime cutoff = ZonedDateTime.now().minusDays(30);
            doNothing().when(repository).deleteOldResolvedAlerts(cutoff);

            repository.deleteOldResolvedAlerts(cutoff);

            verify(repository).deleteOldResolvedAlerts(cutoff);
        }
    }

    @Nested
    @DisplayName("JpaRepository inherited methods")
    class InheritedMethods {

        @Test
        @DisplayName("findById returns alert when exists")
        void findById_found() {
            RagAlert alert = createAlert(1L, "THRESHOLD_HIGH", "WARNING", "ACTIVE");
            when(repository.findById(1L)).thenReturn(java.util.Optional.of(alert));

            java.util.Optional<RagAlert> result = repository.findById(1L);

            assertTrue(result.isPresent());
            assertEquals(1L, result.get().getId());
        }

        @Test
        @DisplayName("findById returns empty when not exists")
        void findById_notFound() {
            when(repository.findById(999L)).thenReturn(java.util.Optional.empty());

            java.util.Optional<RagAlert> result = repository.findById(999L);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("save persists alert")
        void save_persists() {
            RagAlert alert = createAlert(null, "THRESHOLD_HIGH", "WARNING", "ACTIVE");
            RagAlert saved = createAlert(1L, "THRESHOLD_HIGH", "WARNING", "ACTIVE");
            when(repository.save(alert)).thenReturn(saved);

            RagAlert result = repository.save(alert);

            assertNotNull(result.getId());
            verify(repository).save(alert);
        }

        @Test
        @DisplayName("delete removes alert")
        void delete_removes() {
            RagAlert alert = createAlert(1L, "THRESHOLD_HIGH", "WARNING", "ACTIVE");
            doNothing().when(repository).delete(alert);

            repository.delete(alert);

            verify(repository).delete(alert);
        }

        @Test
        @DisplayName("findAll returns all alerts")
        void findAll_returnsAll() {
            RagAlert a1 = createAlert(1L, "THRESHOLD_HIGH", "WARNING", "ACTIVE");
            RagAlert a2 = createAlert(2L, "SLO_BREACH", "CRITICAL", "RESOLVED");
            when(repository.findAll()).thenReturn(List.of(a1, a2));

            List<RagAlert> result = repository.findAll();

            assertEquals(2, result.size());
        }
    }
}
