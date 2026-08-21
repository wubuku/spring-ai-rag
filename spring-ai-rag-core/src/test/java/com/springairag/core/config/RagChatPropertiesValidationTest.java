package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagChatPropertiesValidationTest {

    @Test
    void defaultsAreValid() {
        assertDoesNotThrow(() -> new RagChatProperties().validate());
    }

    @Test
    void rejectsToolPerNameLimitAboveTotalLimit() {
        RagChatProperties properties = new RagChatProperties();
        properties.getAgent().setMaxToolCalls(2);
        properties.getAgent().setMaxToolCallsPerName(3);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsSingleResultLimitAboveCumulativeLimit() {
        RagChatProperties properties = new RagChatProperties();
        properties.getAgent().setMaxToolResultCharacters(5_000);
        properties.getAgent().setMaxToolResultCharactersTotal(4_000);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsImpossibleContextWindowCombination() {
        RagChatProperties properties = new RagChatProperties();
        properties.getContext().setFallbackContextWindow(5_000);
        properties.getContext().setOutputReserveTokens(4_000);
        properties.getContext().setSafetyMarginTokens(1_000);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsEvidenceAndHistoryCombinations() {
        RagChatProperties properties = new RagChatProperties();
        properties.getContext().setMaxHistoryTokens(100);
        properties.getContext().setMaxSummaryTokens(101);
        assertThrows(IllegalStateException.class, properties::validate);

        properties = new RagChatProperties();
        properties.getContext().setMaxRagContextTokens(100);
        properties.getContext().setMinimumModeEvidenceTokens(101);
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsCompactionOutputThatCanConsumeTheEntireSourceBudget() {
        RagChatProperties properties = new RagChatProperties();
        properties.getContext().setCompactionMaxSourceTokens(1_536);
        properties.getContext().setCompactionMaxOutputTokens(1_536);

        assertThrows(IllegalStateException.class, properties::validate);
    }
}
