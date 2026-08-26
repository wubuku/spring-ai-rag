package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.DocumentSyncItemStatus;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Sync Run item keyset cursor 的版本化编解码器。
 */
final class DocumentSyncRunItemCursorCodec {

    static final int MAX_CURSOR_LENGTH = 1024;
    private static final int VERSION = 1;
    private static final int MAX_EXTERNAL_ID_LENGTH = 255;

    private final ObjectMapper objectMapper;

    DocumentSyncRunItemCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    String encode(
            UUID runId,
            DocumentSyncItemStatus statusFilter,
            OffsetDateTime seenAt,
            String externalId) {
        CursorPayload payload = new CursorPayload(
                VERSION,
                Objects.requireNonNull(runId, "runId"),
                statusFilter == null ? "" : statusFilter.name(),
                Objects.requireNonNull(seenAt, "seenAt")
                        .withOffsetSameInstant(ZoneOffset.UTC),
                requireExternalId(externalId));
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            String encoded = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(json);
            if (encoded.length() > MAX_CURSOR_LENGTH) {
                throw invalidCursor();
            }
            return encoded;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode sync run item cursor", error);
        }
    }

    CursorPosition decode(
            String cursor,
            UUID expectedRunId,
            DocumentSyncItemStatus expectedStatusFilter) {
        if (cursor == null || cursor.isBlank()
                || cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            CursorPayload payload = objectMapper.readValue(
                    new String(json, StandardCharsets.UTF_8),
                    CursorPayload.class);
            String expectedStatus = expectedStatusFilter == null
                    ? "" : expectedStatusFilter.name();
            if (payload.v() != VERSION
                    || !Objects.equals(payload.r(), expectedRunId)
                    || !Objects.equals(payload.s(), expectedStatus)
                    || payload.t() == null) {
                throw invalidCursor();
            }
            return new CursorPosition(
                    payload.t().withOffsetSameInstant(ZoneOffset.UTC),
                    requireExternalId(payload.e()));
        } catch (IllegalArgumentException | JsonProcessingException error) {
            throw invalidCursor();
        }
    }

    private static String requireExternalId(String externalId) {
        if (externalId == null
                || externalId.isBlank()
                || externalId.length() > MAX_EXTERNAL_ID_LENGTH
                || !externalId.equals(externalId.trim())) {
            throw invalidCursor();
        }
        for (int index = 0; index < externalId.length(); index++) {
            char current = externalId.charAt(index);
            if (current < 0x20 || current > 0x7e) {
                throw invalidCursor();
            }
        }
        return externalId;
    }

    private static IllegalArgumentException invalidCursor() {
        return new IllegalArgumentException("cursor is invalid");
    }

    record CursorPosition(OffsetDateTime seenAt, String externalId) {
    }

    private record CursorPayload(
            int v,
            UUID r,
            String s,
            OffsetDateTime t,
            String e) {
    }
}
