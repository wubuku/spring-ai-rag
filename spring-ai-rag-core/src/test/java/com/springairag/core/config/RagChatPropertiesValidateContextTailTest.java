package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagChatProperties.validate() 上下文与幂等约束长尾（Batch 570，
 * JaCoCo 驱动）：上下文预算联动约束（output+safety、summary≤history、
 * evidence≤rag、compaction 输出/摘要/源三级、幂等范围上限）。
 */
class RagChatPropertiesValidateContextTailTest {

    private RagChatProperties valid() {
        RagChatProperties properties = new RagChatProperties();
        var c = properties.getContext();
        c.setFallbackContextWindow(1_000);
        c.setOutputReserveTokens(10);
        c.setSafetyMarginTokens(10);
        c.setMaxHistoryTokens(500);
        c.setMaxSummaryTokens(100);
        c.setMinimumModeEvidenceTokens(10);
        c.setMaxRagContextTokens(200);
        c.setCompactionTriggerTokens(10);
        c.setCompactionMaxSourceTokens(200);
        c.setCompactionMaxOutputTokens(50);
        return properties;
    }

    private void assertInvalid(RagChatProperties properties, String key) {
        var error = assertThrows(IllegalStateException.class,
                properties::validate);
        assertTrue(error.getMessage().contains(key), error.getMessage());
    }

    @Test
    void validConfigurationPasses() {
        valid().validate();
    }

    @Test
    void outputReservePlusSafetyMustStayBelowWindow() {
        RagChatProperties properties = valid();
        var c = properties.getContext();
        c.setOutputReserveTokens(995);
        c.setSafetyMarginTokens(10);
        assertInvalid(properties, "output-reserve-tokens");
    }

    @Test
    void summaryMustNotExceedHistory() {
        RagChatProperties properties = valid();
        properties.getContext().setMaxSummaryTokens(600);
        assertInvalid(properties, "max-summary-tokens");
    }

    @Test
    void modeEvidenceMustNotExceedRagContext() {
        RagChatProperties properties = valid();
        properties.getContext().setMinimumModeEvidenceTokens(300);
        assertInvalid(properties, "minimum-mode-evidence-tokens");
    }

    @Test
    void compactionOutputMustNotExceedSummary() {
        RagChatProperties properties = valid();
        properties.getContext().setCompactionMaxOutputTokens(200);
        assertInvalid(properties, "compaction-max-output-tokens must not exceed");
    }

    @Test
    void compactionOutputMustBeLessThanSource() {
        RagChatProperties properties = valid();
        properties.getContext().setCompactionMaxSourceTokens(50);
        assertInvalid(properties, "compaction-max-output-tokens must be less than");
    }

    @Test
    void idempotencyRangesAreEnforced() {
        RagChatProperties properties = valid();
        properties.getIdempotency().setRetentionHours(169);
        assertInvalid(properties, "outside their supported range");

        RagChatProperties snapshotSmall = valid();
        snapshotSmall.getIdempotency().setResponseSnapshotMaxBytes(1);
        assertInvalid(snapshotSmall, "outside their supported range");

        RagChatProperties attemptsOver = valid();
        attemptsOver.getIdempotency().setMaxAttempts(9);
        assertInvalid(attemptsOver, "outside their supported range");
    }

    @Test
    void agentPerNameCannotExceedTotalToolCalls() {
        RagChatProperties properties = valid();
        var agent = properties.getAgent();
        agent.setMaxToolCalls(2);
        agent.setMaxToolCallsPerName(3);
        assertInvalid(properties, "max-tool-calls-per-name");
    }

    @Test
    void perCandidateToolResultCannotExceedTotal() {
        RagChatProperties properties = valid();
        var agent = properties.getAgent();
        agent.setMaxToolResultCharactersTotal(100);
        agent.setMaxToolResultCharacters(200);
        assertInvalid(properties, "max-tool-result-characters");
    }
}
