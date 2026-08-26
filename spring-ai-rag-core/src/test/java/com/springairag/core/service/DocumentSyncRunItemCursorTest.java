package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.DocumentSyncItemStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentSyncRunItemCursorTest {

    private final DocumentSyncRunItemCursorCodec codec =
            new DocumentSyncRunItemCursorCodec(
                    new ObjectMapper().findAndRegisterModules());

    @Test
    void roundTripsRunStatusTimestampAndIdentity() {
        UUID runId = UUID.randomUUID();
        OffsetDateTime seenAt =
                OffsetDateTime.parse("2026-08-26T14:20:00+08:00");

        String encoded = codec.encode(
                runId,
                DocumentSyncItemStatus.FAILED,
                seenAt,
                "record-42");
        DocumentSyncRunItemCursorCodec.CursorPosition decoded =
                codec.decode(encoded, runId, DocumentSyncItemStatus.FAILED);

        assertEquals(
                OffsetDateTime.parse("2026-08-26T06:20:00Z"),
                decoded.seenAt());
        assertEquals("record-42", decoded.externalId());
        assertFalse(encoded.contains("record-42"));
    }

    @Test
    void rejectsRunStatusVersionShapeAndLengthMismatches() {
        UUID runId = UUID.randomUUID();
        String encoded = codec.encode(
                runId,
                null,
                OffsetDateTime.parse("2026-08-26T06:20:00Z"),
                "record-42");

        assertThrows(IllegalArgumentException.class, () ->
                codec.decode(encoded, UUID.randomUUID(), null));
        assertThrows(IllegalArgumentException.class, () ->
                codec.decode(encoded, runId, DocumentSyncItemStatus.FAILED));
        assertThrows(IllegalArgumentException.class, () ->
                codec.decode("not-base64!", runId, null));
        assertThrows(IllegalArgumentException.class, () ->
                codec.decode("x".repeat(
                        DocumentSyncRunItemCursorCodec.MAX_CURSOR_LENGTH + 1),
                        runId,
                        null));

        String unsupported = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("""
                        {"v":2,"r":"%s","s":"","t":"2026-08-26T06:20:00Z","e":"record-42"}
                        """.formatted(runId).getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () ->
                codec.decode(unsupported, runId, null));
    }

    @Test
    void sanitizesCredentialLikeErrorsBeforePersistenceOrResponse() {
        String raw = "provider rejected apiKey=sk-sensitive-value "
                + "Authorization: Bearer bearer-token-value";

        String sanitized = DocumentSyncRunService.sanitizeError(raw);

        assertTrue(sanitized.contains("***REDACTED***"));
        assertFalse(sanitized.contains("sk-sensitive-value"));
        assertFalse(sanitized.contains("bearer-token-value"));
        assertTrue(DocumentSyncRunService.sanitizeError("x".repeat(600))
                .length() <= 500);
    }
}
