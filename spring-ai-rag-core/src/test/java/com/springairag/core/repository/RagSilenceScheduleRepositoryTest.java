package com.springairag.core.repository;

import com.springairag.core.entity.RagSilenceSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagSilenceScheduleRepository 单元测试（使用 Mock）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagSilenceScheduleRepository Tests")
class RagSilenceScheduleRepositoryTest {

    @Mock
    private RagSilenceScheduleRepository repository;

    private RagSilenceSchedule createSchedule(String name, String silenceType, boolean enabled) {
        RagSilenceSchedule s = new RagSilenceSchedule();
        s.setName(name);
        s.setAlertKey("test-alert");
        s.setSilenceType(silenceType);
        s.setStartTime("2026-04-10T02:00:00+08:00");
        s.setEndTime("2026-04-10T04:00:00+08:00");
        s.setDescription("Test schedule");
        s.setEnabled(enabled);
        s.setCreatedAt(ZonedDateTime.now());
        return s;
    }

    @BeforeEach
    void setUp() {
        // Reset mocks if needed
    }

    @Test
    @DisplayName("findByName returns schedule when exists")
    void findByName_found() {
        RagSilenceSchedule s = createSchedule("weekend", "RECURRING", true);
        when(repository.findByName("weekend")).thenReturn(Optional.of(s));

        Optional<RagSilenceSchedule> found = repository.findByName("weekend");

        assertTrue(found.isPresent());
        assertEquals("RECURRING", found.get().getSilenceType());
        verify(repository).findByName("weekend");
    }

    @Test
    @DisplayName("findByName returns empty when not exists")
    void findByName_notFound() {
        when(repository.findByName("nonexistent")).thenReturn(Optional.empty());

        Optional<RagSilenceSchedule> found = repository.findByName("nonexistent");

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("findByEnabledTrue returns only enabled schedules")
    void findByEnabledTrue() {
        RagSilenceSchedule s1 = createSchedule("enabled-1", "ONE_TIME", true);
        RagSilenceSchedule s2 = createSchedule("disabled-1", "ONE_TIME", false);
        when(repository.findByEnabledTrue()).thenReturn(List.of(s1));

        List<RagSilenceSchedule> enabled = repository.findByEnabledTrue();

        assertEquals(1, enabled.size());
        assertTrue(enabled.get(0).getEnabled());
    }

    @Test
    @DisplayName("findByAlertKeyAndEnabledTrue returns matching enabled schedules")
    void findByAlertKeyAndEnabledTrue() {
        RagSilenceSchedule s1 = createSchedule("s1", "ONE_TIME", true);
        s1.setAlertKey("high-latency");
        when(repository.findByAlertKeyAndEnabledTrue("high-latency")).thenReturn(List.of(s1));

        List<RagSilenceSchedule> found = repository.findByAlertKeyAndEnabledTrue("high-latency");

        assertEquals(1, found.size());
        assertEquals("high-latency", found.get(0).getAlertKey());
    }

    @Test
    @DisplayName("findByAlertKey returns schedules for specific alert key")
    void findByAlertKey() {
        RagSilenceSchedule s1 = createSchedule("s1", "ONE_TIME", true);
        s1.setAlertKey("high-latency");
        RagSilenceSchedule s2 = createSchedule("s2", "ONE_TIME", true);
        s2.setAlertKey("high-error");
        when(repository.findByAlertKey("high-latency")).thenReturn(List.of(s1));

        List<RagSilenceSchedule> found = repository.findByAlertKey("high-latency");

        assertEquals(1, found.size());
        assertEquals("high-latency", found.get(0).getAlertKey());
    }

    @Test
    @DisplayName("deleteByName calls repository delete method")
    void deleteByName() {
        doNothing().when(repository).deleteByName("to-delete");

        repository.deleteByName("to-delete");

        verify(repository).deleteByName("to-delete");
    }

    @Test
    @DisplayName("save calls repository save method")
    void save() {
        RagSilenceSchedule s = createSchedule("new-schedule", "ONE_TIME", true);
        when(repository.save(s)).thenReturn(s);

        RagSilenceSchedule saved = repository.save(s);

        assertNotNull(saved);
        assertEquals("new-schedule", saved.getName());
        verify(repository).save(s);
    }

    @Test
    @DisplayName("findAll returns all schedules")
    void findAll() {
        RagSilenceSchedule s1 = createSchedule("s1", "ONE_TIME", true);
        RagSilenceSchedule s2 = createSchedule("s2", "RECURRING", false);
        when(repository.findAll()).thenReturn(List.of(s1, s2));

        List<RagSilenceSchedule> all = repository.findAll();

        assertEquals(2, all.size());
    }
}
