package com.springairag.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RetrievalOptionsTest {

    @Test
    void rejectsNonFiniteVectorWeight() {
        assertThrows(IllegalArgumentException.class, () -> options(Double.NaN, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> options(Double.POSITIVE_INFINITY, 0.5));
    }

    @Test
    void rejectsNonFiniteFulltextWeight() {
        assertThrows(IllegalArgumentException.class, () -> options(0.5, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> options(0.5, Double.NEGATIVE_INFINITY));
    }

    private RetrievalOptions options(double vectorWeight, double fulltextWeight) {
        return new RetrievalOptions(
                5, 0.25, true, true, vectorWeight, fulltextWeight);
    }
}
