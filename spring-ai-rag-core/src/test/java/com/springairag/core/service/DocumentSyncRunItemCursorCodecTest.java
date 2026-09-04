package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.springairag.api.enums.DocumentSyncItemStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DocumentSyncRunItemCursorCodec} 的契约测试：游标是跨请求的对外 token，
 * 编码、绑定校验与拒绝语义都必须保持稳定。
 */
class DocumentSyncRunItemCursorCodecTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final String EXTERNAL_ID = "ext-0001";

    private DocumentSyncRunItemCursorCodec codec;

    @BeforeEach
    void setUp() {
        codec = new DocumentSyncRunItemCursorCodec(new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    private OffsetDateTime seenAt() {
        return OffsetDateTime.of(2026, 9, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    }

    @Test
    void roundtripPreservesPositionAndFilter() {
        String cursor = codec.encode(
                RUN_ID, DocumentSyncItemStatus.FAILED, seenAt(), EXTERNAL_ID);

        DocumentSyncRunItemCursorCodec.CursorPosition position =
                codec.decode(cursor, RUN_ID, DocumentSyncItemStatus.FAILED);

        assertEquals(seenAt(), position.seenAt());
        assertEquals(EXTERNAL_ID, position.externalId());
    }

    @Test
    void roundtripWorksWithoutStatusFilter() {
        String cursor = codec.encode(RUN_ID, null, seenAt(), EXTERNAL_ID);

        DocumentSyncRunItemCursorCodec.CursorPosition position =
                codec.decode(cursor, RUN_ID, null);

        assertEquals(EXTERNAL_ID, position.externalId());
    }

    @Test
    void seenAtIsNormalizedToUtcInstant() {
        OffsetDateTime local = seenAt().withOffsetSameInstant(ZoneOffset.ofHours(2));
        String cursor = codec.encode(RUN_ID, null, local, EXTERNAL_ID);

        DocumentSyncRunItemCursorCodec.CursorPosition position =
                codec.decode(cursor, RUN_ID, null);

        assertEquals(ZoneOffset.UTC, position.seenAt().getOffset());
        assertEquals(seenAt().toInstant(), position.seenAt().toInstant());
    }

    @Test
    void decodeRejectsCursorBoundToAnotherRunOrFilter() {
        String cursor = codec.encode(
                RUN_ID, DocumentSyncItemStatus.FAILED, seenAt(), EXTERNAL_ID);
        UUID otherRun = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(cursor, otherRun, DocumentSyncItemStatus.FAILED));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(cursor, RUN_ID, DocumentSyncItemStatus.APPLIED));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(cursor, RUN_ID, null));
    }

    @Test
    void decodeRejectsNullBlankOversizedAndMalformedCursors() {
        String oversized = "a".repeat(1025);

        assertThrows(IllegalArgumentException.class, () -> codec.decode(null, RUN_ID, null));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(" ", RUN_ID, null));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(oversized, RUN_ID, null));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode("not-base64-!!", RUN_ID, null));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(
                        Base64.getUrlEncoder().withoutPadding()
                                .encodeToString("{\"v\":2}".getBytes()),
                        RUN_ID, null));
    }

    @Test
    void encodeRejectsUnsafeExternalIds() {
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(RUN_ID, null, seenAt(), " "));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(RUN_ID, null, seenAt(), " padded "));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(RUN_ID, null, seenAt(), "with\u0000control"));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(RUN_ID, null, seenAt(), "中文-non-ascii"));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(RUN_ID, null, seenAt(), "x".repeat(256)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(RUN_ID, null, seenAt(), null));
    }

    @Test
    void maxCursorLengthAcceptsTheWorstLegalExternalId() {
        String worst = "x".repeat(255);
        String cursor = codec.encode(RUN_ID, null, seenAt(), worst);

        DocumentSyncRunItemCursorCodec.CursorPosition position =
                codec.decode(cursor, RUN_ID, null);

        assertEquals(worst, position.externalId());
    }
}
