package com.springairag.core.alertdelivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** 投递回执 keyset cursor 的版本化编解码器。 */
final class AlertNotificationCursorCodec {

    private static final int VERSION = 1;
    private static final int MAX_LENGTH = 1024;
    private final ObjectMapper objectMapper;

    AlertNotificationCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String encode(
            String status,
            String provider,
            Long alertId,
            OffsetDateTime createdAt,
            UUID id) {
        CursorPayload payload = new CursorPayload(
                VERSION,
                value(status),
                value(provider),
                alertId,
                createdAt.withOffsetSameInstant(ZoneOffset.UTC),
                id);
        try {
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            if (encoded.length() > MAX_LENGTH) {
                throw invalid();
            }
            return encoded;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "Cannot encode alert notification cursor", error);
        }
    }

    CursorPosition decode(
            String cursor,
            String expectedStatus,
            String expectedProvider,
            Long expectedAlertId) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_LENGTH) {
            throw invalid();
        }
        try {
            CursorPayload payload = objectMapper.readValue(
                    new String(Base64.getUrlDecoder().decode(cursor),
                            StandardCharsets.UTF_8),
                    CursorPayload.class);
            if (payload.v() != VERSION
                    || !Objects.equals(payload.s(), value(expectedStatus))
                    || !Objects.equals(payload.p(), value(expectedProvider))
                    || !Objects.equals(payload.a(), expectedAlertId)
                    || payload.t() == null
                    || payload.i() == null) {
                throw invalid();
            }
            return new CursorPosition(
                    payload.t().withOffsetSameInstant(ZoneOffset.UTC),
                    payload.i());
        } catch (IllegalArgumentException | JsonProcessingException error) {
            throw invalid();
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("cursor is invalid");
    }

    record CursorPosition(OffsetDateTime createdAt, UUID id) {
    }

    private record CursorPayload(
            int v,
            String s,
            String p,
            Long a,
            OffsetDateTime t,
            UUID i) {
    }
}
