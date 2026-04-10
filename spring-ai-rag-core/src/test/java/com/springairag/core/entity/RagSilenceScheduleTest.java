package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagSilenceSchedule entity.
 */
class RagSilenceScheduleTest {

    @Test
    void defaultValues_enabledIsTrue() {
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        assertTrue(schedule.getEnabled());
    }

    @Test
    void defaultValues_createdAtIsNotNull() {
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        assertNotNull(schedule.getCreatedAt());
    }

    @Test
    void allFields_setAndGet() {
        ZonedDateTime now = ZonedDateTime.now();

        RagSilenceSchedule schedule = new RagSilenceSchedule();
        schedule.setId(1L);
        schedule.setName("Weekend Silence");
        schedule.setAlertKey("high-latency");
        schedule.setSilenceType("RECURRING");
        schedule.setStartTime("0 0 0 * * SAT");
        schedule.setEndTime("0 0 0 * * MON");
        schedule.setDescription("Silence alerts during weekends");
        schedule.setEnabled(false);
        schedule.setMetadata(Map.of("timezone", "UTC"));
        schedule.setCreatedAt(now);
        schedule.setUpdatedAt(now);

        assertEquals(1L, schedule.getId());
        assertEquals("Weekend Silence", schedule.getName());
        assertEquals("high-latency", schedule.getAlertKey());
        assertEquals("RECURRING", schedule.getSilenceType());
        assertEquals("0 0 0 * * SAT", schedule.getStartTime());
        assertEquals("0 0 0 * * MON", schedule.getEndTime());
        assertEquals("Silence alerts during weekends", schedule.getDescription());
        assertFalse(schedule.getEnabled());
        assertEquals("UTC", schedule.getMetadata().get("timezone"));
        assertEquals(now, schedule.getCreatedAt());
        assertEquals(now, schedule.getUpdatedAt());
    }

    @Test
    void silenceTypes() {
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        schedule.setSilenceType("ONE_TIME");
        assertEquals("ONE_TIME", schedule.getSilenceType());

        schedule.setSilenceType("RECURRING");
        assertEquals("RECURRING", schedule.getSilenceType());
    }

    @Test
    void metadata_jsonMapSerialization() {
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        Map<String, Object> metadata = Map.of(
                "timezone", "Asia/Shanghai",
                "notifyUsers", true,
                "priority", 10
        );
        schedule.setMetadata(metadata);

        assertEquals("Asia/Shanghai", schedule.getMetadata().get("timezone"));
        assertEquals(true, schedule.getMetadata().get("notifyUsers"));
        assertEquals(10, schedule.getMetadata().get("priority"));
    }

    @Test
    void nullAlertKey_representsAllAlerts() {
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        schedule.setAlertKey(null);
        assertNull(schedule.getAlertKey());
    }

    @Test
    void optionalFields_canBeNull() {
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        schedule.setAlertKey(null);
        schedule.setStartTime(null);
        schedule.setEndTime(null);
        schedule.setDescription(null);
        schedule.setMetadata(null);
        schedule.setUpdatedAt(null);

        assertNull(schedule.getAlertKey());
        assertNull(schedule.getStartTime());
        assertNull(schedule.getEndTime());
        assertNull(schedule.getDescription());
        assertNull(schedule.getMetadata());
        assertNull(schedule.getUpdatedAt());
    }

    @Test
    void enabledToggle() {
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        assertTrue(schedule.getEnabled());

        schedule.setEnabled(false);
        assertFalse(schedule.getEnabled());

        schedule.setEnabled(true);
        assertTrue(schedule.getEnabled());
    }

}
