package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置默认值契约：四个特性分组的出厂默认与 setter 往返。
 * 运维文档与部署清单以这些默认值为基线，误改即测试红。
 */
class ConfigurationDefaultsTest {

    @Test
    void structuredRecordDefaultsMatchTheDocumentedBaseline() {
        RagStructuredRecordProperties properties = new RagStructuredRecordProperties();

        assertEquals(1_048_576, properties.getMaxJsonbPayloadBytes());
        assertEquals(10_000, properties.getMaxRetrievalTextChars());
        assertEquals(20, properties.getMaxBatchSize());
        assertEquals(10_485_760, properties.getMaxBatchPayloadBytes());
        assertEquals(20, properties.getMaxSearchResults());
        assertEquals(16_384, properties.getMaxPayloadFilterBytes());
        assertEquals(8, properties.getMaxPayloadFilterDepth());
        assertFalse(properties.isAgentToolEnabled());
        assertEquals(5, properties.getAgentToolMaxResults());
        assertEquals(32_768, properties.getAgentToolMaxPayloadBytes());
    }

    @Test
    void structuredRecordSettersRoundTrip() {
        RagStructuredRecordProperties properties = new RagStructuredRecordProperties();
        properties.setMaxJsonbPayloadBytes(2_097_152);
        properties.setAgentToolEnabled(true);
        properties.setAgentToolMaxResults(10);

        assertEquals(2_097_152, properties.getMaxJsonbPayloadBytes());
        assertTrue(properties.isAgentToolEnabled());
        assertEquals(10, properties.getAgentToolMaxResults());
    }

    @Test
    void evaluationDefaultsMatchTheDocumentedBaseline() {
        RagEvaluationProperties properties = new RagEvaluationProperties();

        assertFalse(properties.isManagedSuitesEnabled());
        assertTrue(properties.isCitationValidationEnabled());
        assertEquals(1, properties.getMaxConcurrentRuns());
        assertEquals(4, properties.getRunConcurrency());
        assertEquals(200, properties.getMaxCasesPerVersion());
        assertEquals(4, properties.getMaxVariantsPerRun());
        assertEquals(50, properties.getSemanticBatchLimit());
    }

    @Test
    void evaluationSettersRoundTrip() {
        RagEvaluationProperties properties = new RagEvaluationProperties();
        properties.setManagedSuitesEnabled(true);
        properties.setCitationValidationEnabled(false);
        properties.setMaxConcurrentRuns(2);

        assertTrue(properties.isManagedSuitesEnabled());
        assertFalse(properties.isCitationValidationEnabled());
        assertEquals(2, properties.getMaxConcurrentRuns());
    }

    @Test
    void retrievalDiagnosticsDefaultsMatchTheDocumentedBaseline() {
        RagRetrievalDiagnosticsProperties properties =
                new RagRetrievalDiagnosticsProperties();

        assertTrue(properties.isEnabled());
        assertTrue(properties.isPersist());
        assertEquals(7, properties.getRetentionDays());
        assertFalse(properties.isStoreQueryText());
        assertEquals(32_768, properties.getMaxDetailBytes());
        assertEquals(1_500, properties.getProbeTimeoutMs());
    }

    @Test
    void retrievalDiagnosticsSettersRoundTrip() {
        RagRetrievalDiagnosticsProperties properties =
                new RagRetrievalDiagnosticsProperties();
        properties.setPersist(false);
        properties.setStoreQueryText(true);
        properties.setRetentionDays(14);
        properties.setProbeTimeoutMs(2_500);

        assertFalse(properties.isPersist());
        assertTrue(properties.isStoreQueryText());
        assertEquals(14, properties.getRetentionDays());
        assertEquals(2_500, properties.getProbeTimeoutMs());
    }

    @Test
    void documentLifecycleDefaultsMatchTheDocumentedBaseline() {
        RagDocumentLifecycleProperties properties =
                new RagDocumentLifecycleProperties();

        assertTrue(properties.isStrictExternalCas());
        assertTrue(properties.isAllowNonDefaultNamespace());
        assertEquals(24, properties.getIdempotencyTtlHours());
        assertFalse(properties.isSyncRunsEnabled());
        assertFalse(properties.isVersionRestoreEnabled());
        assertFalse(properties.isRelocationEnabled());
        assertFalse(properties.isDerivationRepairEnabled());
        assertEquals(1_000, properties.getSyncRunMaxMissingAbsolute());
        assertEquals(20, properties.getSyncRunMaxMissingPercent());
    }

    @Test
    void documentLifecycleSettersRoundTrip() {
        RagDocumentLifecycleProperties properties =
                new RagDocumentLifecycleProperties();
        properties.setStrictExternalCas(false);
        properties.setSyncRunsEnabled(true);
        properties.setVersionRestoreEnabled(true);
        properties.setRelocationEnabled(true);
        properties.setDerivationRepairEnabled(true);
        properties.setIdempotencyTtlHours(48);
        properties.setSyncRunMaxMissingAbsolute(2_000);
        properties.setSyncRunMaxMissingPercent(30);

        assertFalse(properties.isStrictExternalCas());
        assertTrue(properties.isSyncRunsEnabled());
        assertTrue(properties.isVersionRestoreEnabled());
        assertTrue(properties.isRelocationEnabled());
        assertTrue(properties.isDerivationRepairEnabled());
        assertEquals(48, properties.getIdempotencyTtlHours());
        assertEquals(2_000, properties.getSyncRunMaxMissingAbsolute());
        assertEquals(30, properties.getSyncRunMaxMissingPercent());
    }
}
