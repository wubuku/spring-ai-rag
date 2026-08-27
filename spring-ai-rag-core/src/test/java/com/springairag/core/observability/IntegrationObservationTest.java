package com.springairag.core.observability;

import com.springairag.api.enums.IntegrationOperation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntegrationObservationTest {

    @Test
    void normalizesBucketDurationAndCollectionIds() {
        List<Long> collectionIds = new ArrayList<>();
        for (long id = 105; id >= 1; id--) {
            collectionIds.add(id);
        }
        collectionIds.add(1L);
        IntegrationObservation observation = new IntegrationObservation(
                Instant.parse("2026-08-27T14:35:12Z"),
                "DATABASE_API_KEY",
                "principal-1",
                IntegrationOperation.JSON_RECORD_SEARCH,
                200,
                -10,
                collectionIds);

        assertEquals(
                Instant.parse("2026-08-27T14:00:00Z"),
                observation.bucketStart());
        assertEquals(0, observation.durationMs());
        assertEquals(IntegrationObservation.MAX_COLLECTION_IDS,
                observation.authorizedCollectionIds().size());
        assertEquals(List.of(1L, 2L, 3L),
                observation.authorizedCollectionIds().subList(0, 3));
        assertEquals(100L,
                observation.authorizedCollectionIds().get(
                        observation.authorizedCollectionIds().size() - 1));
    }

    @Test
    void rejectsInvalidStatusAndSensitiveOrUnboundedIdentityText() {
        assertThrows(IllegalArgumentException.class,
                () -> observation("principal\n", "DATABASE_API_KEY"));
        assertThrows(IllegalArgumentException.class,
                () -> observation("principal-1", "DATABASE_API_KEY\n"));
        assertThrows(IllegalArgumentException.class,
                () -> new IntegrationObservation(
                        Instant.now(),
                        "DATABASE_API_KEY",
                        "principal-1",
                        IntegrationOperation.JSON_RECORD_SEARCH,
                        99,
                        1,
                        List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new IntegrationObservation(
                        Instant.now(),
                        "DATABASE_API_KEY",
                        "x".repeat(65),
                        IntegrationOperation.JSON_RECORD_SEARCH,
                        200,
                        1,
                        List.of()));
    }

    private IntegrationObservation observation(String principalRef, String principalType) {
        return new IntegrationObservation(
                Instant.now(),
                principalType,
                principalRef,
                IntegrationOperation.JSON_RECORD_SEARCH,
                200,
                1,
                List.of());
    }
}
