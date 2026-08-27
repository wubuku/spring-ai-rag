package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagEmbeddingJobPropertiesTest {

    @Test
    void recoveryScanDefaultsToThirtySeconds() {
        assertEquals(30_000,
                new RagEmbeddingJobProperties().getPollIntervalMs());
    }

    @Test
    void recoveryScanCannotBeConfiguredBelowTenSeconds() {
        RagEmbeddingJobProperties properties =
                new RagEmbeddingJobProperties();

        properties.setPollIntervalMs(250);

        assertEquals(10_000, properties.getPollIntervalMs());
    }
}
