package com.springairag.core.alertdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AlertNotificationCursorCodec} 的契约测试：回执分页游标的编码、
 * 过滤器绑定与拒绝语义必须保持稳定。
 */
class AlertNotificationCursorCodecTest {

    private static final UUID ID = UUID.randomUUID();

    private AlertNotificationCursorCodec codec;

    @BeforeEach
    void setUp() {
        codec = new AlertNotificationCursorCodec(new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    private OffsetDateTime createdAt() {
        return OffsetDateTime.of(2026, 9, 5, 8, 30, 0, 0, ZoneOffset.UTC);
    }

    @Test
    void roundtripPreservesPositionAndFilters() {
        String cursor = codec.encode("PENDING", "webhook", 42L, createdAt(), ID);

        AlertNotificationCursorCodec.CursorPosition position =
                codec.decode(cursor, "PENDING", "webhook", 42L);

        assertEquals(createdAt(), position.createdAt());
        assertEquals(ID, position.id());
    }

    @Test
    void roundtripWorksWithNullStatusAndProviderFilters() {
        String cursor = codec.encode(null, null, null, createdAt(), ID);

        AlertNotificationCursorCodec.CursorPosition position =
                codec.decode(cursor, null, null, null);

        assertEquals(ID, position.id());
    }

    @Test
    void decodeRejectsCursorBoundToOtherFilters() {
        String cursor = codec.encode("PENDING", "webhook", 42L, createdAt(), ID);

        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(cursor, "SENT", "webhook", 42L));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(cursor, "PENDING", "smtp", 42L));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(cursor, "PENDING", "webhook", 43L));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(cursor, null, "webhook", 42L));
    }

    @Test
    void decodeRejectsNullBlankOversizedAndMalformedCursors() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode("a".repeat(1025), null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode("%%invalid%%", null, null, null));
    }

    @Test
    void seenAtIsNormalizedToUtcInstant() {
        OffsetDateTime local = createdAt().withOffsetSameInstant(ZoneOffset.ofHours(-5));
        String cursor = codec.encode("PENDING", "webhook", 42L, local, ID);

        AlertNotificationCursorCodec.CursorPosition position =
                codec.decode(cursor, "PENDING", "webhook", 42L);

        assertEquals(ZoneOffset.UTC, position.createdAt().getOffset());
        assertEquals(createdAt().toInstant(), position.createdAt().toInstant());
    }
}
