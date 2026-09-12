package com.springairag.core.service;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * readOffsetDateTime 时间类型归一（Batch 349）：OffsetDateTime 原
 * 样返回、Timestamp/Instant 统一为 UTC 偏移、java.sql.Date 按系统
 * 时区当日零点、不支持类型（含 null）抛 ISE。
 */
class DocumentSyncRunReadOffsetDateTimeTest {

    private static OffsetDateTime convert(Object value) throws Exception {
        var method = DocumentSyncRunService.class
                .getDeclaredMethod("readOffsetDateTime", Object.class);
        method.setAccessible(true);
        return (OffsetDateTime) method.invoke(null, value);
    }

    @Test
    void offsetDateTimePassesThroughAsIs() throws Exception {
        OffsetDateTime value = OffsetDateTime.parse("2026-09-13T01:02:03+08:00");

        assertSame(value, convert(value));
    }

    @Test
    void timestampConvertsToUtcOffset() throws Exception {
        Timestamp timestamp = Timestamp.from(
                Instant.parse("2026-09-13T01:02:03Z"));

        OffsetDateTime converted = convert(timestamp);

        assertEquals("2026-09-13T01:02:03Z", converted.toInstant().toString());
        assertEquals(ZoneOffset.UTC, converted.getOffset());
    }

    @Test
    void instantConvertsToUtcOffset() throws Exception {
        OffsetDateTime converted = convert(
                Instant.parse("2026-09-13T05:06:07Z"));

        assertEquals("2026-09-13T05:06:07Z", converted.toInstant().toString());
        assertEquals(ZoneOffset.UTC, converted.getOffset());
    }

    @Test
    void sqlDateConvertsToStartOfDayInSystemZone() throws Exception {
        java.sql.Date date = java.sql.Date.valueOf("2026-09-13");

        OffsetDateTime converted = convert(date);

        assertEquals("2026-09-13T00:00",
                converted.atZoneSameInstant(java.time.ZoneId.systemDefault())
                        .toLocalDateTime().toString().substring(0, 16));
    }

    @Test
    void unsupportedTypeAndNullThrowIllegalState() throws Exception {
        var method = DocumentSyncRunService.class
                .getDeclaredMethod("readOffsetDateTime", Object.class);
        method.setAccessible(true);

        var supportedType = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(null, 42));
        assertTrue(String.valueOf(supportedType.getCause())
                .contains("Unsupported sync-run timestamp type"));

        var nullValue = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(null, (Object) null));
        assertTrue(String.valueOf(nullValue.getCause())
                .contains("null"));
    }
}
