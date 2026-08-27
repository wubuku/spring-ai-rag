package com.springairag.core.observability;

import com.springairag.api.enums.IntegrationOperation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一个外部业务接入 HTTP 请求的安全、有限观测快照。
 */
public record IntegrationObservation(
        Instant bucketStart,
        String principalType,
        String principalRef,
        IntegrationOperation operation,
        int httpStatus,
        long durationMs,
        List<Long> authorizedCollectionIds) {

    public static final int MAX_COLLECTION_IDS = 100;
    public static final int MAX_PRINCIPAL_REF_LENGTH = 64;

    public IntegrationObservation {
        bucketStart = bucketStart == null
                ? Instant.now().truncatedTo(ChronoUnit.HOURS)
                : bucketStart.truncatedTo(ChronoUnit.HOURS);
        principalType = requiredAscii(principalType, 32, "principalType");
        principalRef = requiredAscii(
                principalRef, MAX_PRINCIPAL_REF_LENGTH, "principalRef");
        if (operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        if (httpStatus < 100 || httpStatus > 599) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
        durationMs = Math.max(0, durationMs);
        if (authorizedCollectionIds == null) {
            authorizedCollectionIds = List.of();
        } else {
            LinkedHashSet<Long> unique = new LinkedHashSet<>();
            authorizedCollectionIds.stream()
                    .filter(id -> id != null && id > 0)
                    .sorted(Comparator.naturalOrder())
                    .forEach(id -> {
                        if (unique.size() < MAX_COLLECTION_IDS) {
                            unique.add(id);
                        }
                    });
            authorizedCollectionIds = List.copyOf(unique);
        }
    }

    private static String requiredAscii(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(ch -> ch < 0x20 || ch > 0x7e)) {
            throw new IllegalArgumentException(
                    name + " must contain printable ASCII characters within 1-" + max);
        }
        return value;
    }
}
