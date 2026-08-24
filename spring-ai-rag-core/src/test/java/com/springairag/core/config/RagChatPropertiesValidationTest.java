package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagChatPropertiesValidationTest {

    @Test
    void defaultsAreValid() {
        assertDoesNotThrow(() -> new RagChatProperties().validate());
    }

    @Test
    void knowledgeQueryBudgetKeepsDefaultAndClampsEffectiveVariants() {
        RagChatProperties.KnowledgeProperties knowledge =
                new RagChatProperties().getKnowledge();

        assertEquals(2, knowledge.getQueryExpanderVariants());
        assertEquals(3, knowledge.getMaxRetrievalQueries());
        assertEquals(2, knowledge.getEffectiveQueryExpanderVariants());
        assertEquals(3, knowledge.getPlannedRetrievalQueries());

        knowledge.setQueryExpanderVariants(5);
        assertEquals(5, knowledge.getQueryExpanderVariants());
        assertEquals(2, knowledge.getEffectiveQueryExpanderVariants());
        assertEquals(3, knowledge.getPlannedRetrievalQueries());
        assertEquals(true, knowledge.isQueryExpansionBudgetLimited());

        knowledge.setMaxRetrievalQueries(99);
        assertEquals(5, knowledge.getMaxRetrievalQueries());
        assertEquals(4, knowledge.getEffectiveQueryExpanderVariants());
        assertEquals(5, knowledge.getPlannedRetrievalQueries());
    }

    @Test
    void knowledgeQueryBudgetOneReservesTheOriginalQuery() {
        RagChatProperties.KnowledgeProperties knowledge =
                new RagChatProperties().getKnowledge();
        knowledge.setQueryExpanderVariants(5);
        knowledge.setMaxRetrievalQueries(1);

        assertEquals(0, knowledge.getEffectiveQueryExpanderVariants());
        assertEquals(1, knowledge.getPlannedRetrievalQueries());
        assertEquals(true, knowledge.isQueryExpansionBudgetLimited());

        knowledge.setQueryExpanderIncludeOriginal(false);
        assertEquals(1, knowledge.getEffectiveQueryExpanderVariants());
        assertEquals(1, knowledge.getPlannedRetrievalQueries());
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
