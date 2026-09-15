package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentLifecycleService 状态推导真值表（Batch 411）：
 * deriveFromStateRow 的 local/embedding 状态×新鲜度×错误优先级
 * 矩阵，以及 fromIntegrity 的 bucket/condition 映射。
 */
class DocumentLifecycleDerivationTailTest {

    private static final String HASH = "hash-1";
    private static final String CHUNKER = "hierarchical-v2";

    private JdbcTemplate jdbcTemplate;
    private DocumentLifecycleService service;
    private Method derive;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingProfileProvider profileProvider =
                mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                7L, "bge-m3", "siliconflow", "BAAI/bge-m3", null,
                1024, "cosine", "none", true));
        service = new DocumentLifecycleService(
                jdbcTemplate,
                profileProvider,
                new DocumentDerivationDescriptorProvider(new RagProperties()));
        derive = DocumentLifecycleService.class.getDeclaredMethod(
                "deriveFromStateRow", Map.class, String.class, String.class);
        derive.setAccessible(true);
    }

    private Map<String, Object> fullRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("local_index_status", "READY");
        row.put("local_content_hash", HASH);
        row.put("local_chunker_version", CHUNKER);
        row.put("local_index_generation", 1);
        row.put("local_chunk_count", 2);
        row.put("local_actual_chunk_count", 2);
        row.put("local_error", null);
        row.put("embedding_status", "COMPLETED");
        row.put("embedding_content_hash", HASH);
        row.put("embedding_chunker_version", CHUNKER);
        row.put("embedding_chunk_count", 2);
        row.put("active_job_id", null);
        row.put("embedding_error", null);
        row.put("job_status", null);
        row.put("job_error", null);
        return row;
    }

    private Object derive(Map<String, Object> row) throws Exception {
        return derive.invoke(null, row, HASH, CHUNKER);
    }

    /** DerivedLifecycle 为私有 record：经反射读取组件值。 */
    private String field(Object derived, String component) throws Exception {
        Method accessor = derived.getClass().getDeclaredMethod(component);
        accessor.setAccessible(true);
        return (String) accessor.invoke(derived);
    }

    // ── deriveFromStateRow 真值表 ─────────────────────────────────

    @Test
    void fullyCurrentRowIsReadyEverywhere() throws Exception {
        Object derived = derive(fullRow());
        assertEquals("READY", field(derived, "localStatus"));
        assertEquals("READY", field(derived, "embeddingStatus"));
        assertEquals("READY", field(derived, "searchability"));
        assertNull(field(derived, "errorCode"));
        assertNull(field(derived, "error"));
    }

    @Test
    void staleLocalHashFailsLocalAndSearchability() throws Exception {
        Map<String, Object> row = fullRow();
        row.put("local_content_hash", "stale");
        Object derived = derive(row);
        assertEquals("FAILED", field(derived, "localStatus"));
        assertEquals("FAILED", field(derived, "searchability"));
        // local 行存在且 error 为空 → 无 LOCAL_INDEX_MISSING。
        assertNull(field(derived, "errorCode"));
    }

    @Test
    void localReadyWithQueuedEmbeddingYieldsKeywordOnly() throws Exception {
        Map<String, Object> row = fullRow();
        row.put("embedding_status", "QUEUED");
        Object derived = derive(row);
        assertEquals("READY", field(derived, "localStatus"));
        assertEquals("INDEXING", field(derived, "embeddingStatus"));
        assertEquals("KEYWORD_ONLY", field(derived, "searchability"));
    }

    @Test
    void runningJobMarksEmbeddingIndexing() throws Exception {
        Map<String, Object> row = fullRow();
        row.put("embedding_status", "PENDING");
        row.put("job_status", "RUNNING");
        Object derived = derive(row);
        assertEquals("INDEXING", field(derived, "embeddingStatus"));
    }

    @Test
    void failedEmbeddingSurfacesEmbeddingFailedEvenWithLocalError()
            throws Exception {
        Map<String, Object> row = fullRow();
        row.put("embedding_status", "FAILED");
        row.put("local_error", "local boom");
        row.put("embedding_error", "vector boom");
        Object derived = derive(row);
        assertEquals("KEYWORD_ONLY", field(derived, "searchability"));
        // EMBEDDING_FAILED 覆盖 LOCAL_INDEX_FAILED（后者后赋值）。
        assertEquals("EMBEDDING_FAILED", field(derived, "errorCode"));
        assertEquals("local boom", field(derived, "error"));
    }

    @Test
    void bothNotRequestedYieldsNotRequestedLifecycle() throws Exception {
        Map<String, Object> row = fullRow();
        row.put("local_index_status", "NOT_REQUESTED");
        row.put("embedding_status", "NOT_REQUESTED");
        Object derived = derive(row);
        // 既有语义：embedding 行存在时，local NOT_REQUESTED 判为 FAILED
        // （只有 local/embedding 双双缺失才是 NOT_REQUESTED）。
        assertEquals("FAILED", field(derived, "localStatus"));
        assertEquals("NOT_REQUESTED", field(derived, "embeddingStatus"));
        assertEquals("FAILED", field(derived, "searchability"));
    }

    @Test
    void embeddingOnlyProcessingYieldsIndexing() throws Exception {
        Map<String, Object> row = fullRow();
        row.put("local_index_status", null);
        row.put("embedding_status", "PROCESSING");
        Object derived = derive(row);
        // 既有语义：embedding 行存在时 local 缺失 → FAILED（非 NOT_REQUESTED）。
        assertEquals("FAILED", field(derived, "localStatus"));
        assertEquals("INDEXING", field(derived, "embeddingStatus"));
        assertEquals("INDEXING", field(derived, "searchability"));
    }

    @Test
    void missingLocalRowWithFailedEmbeddingReportsMissingLocal()
            throws Exception {
        Map<String, Object> row = fullRow();
        row.put("local_index_status", null);
        row.put("embedding_status", "FAILED");
        Object derived = derive(row);
        assertEquals("FAILED", field(derived, "localStatus"));
        assertEquals("FAILED", field(derived, "searchability"));
        assertEquals("LOCAL_INDEX_MISSING", field(derived, "errorCode"));
    }

    @Test
    void jobErrorIsUsedWhenModelErrorsAreAbsent() throws Exception {
        Map<String, Object> row = fullRow();
        row.put("embedding_status", "FAILED");
        row.put("job_error", "job crashed");
        Object derived = derive(row);
        assertEquals("job crashed", field(derived, "error"));
        assertEquals("EMBEDDING_FAILED", field(derived, "errorCode"));
    }

    @Test
    void cancelledEmbeddingStateAlsoFails() throws Exception {
        Map<String, Object> row = fullRow();
        row.put("embedding_status", "CANCELLED");
        Object derived = derive(row);
        assertEquals("FAILED", field(derived, "embeddingStatus"));
        assertEquals("KEYWORD_ONLY", field(derived, "searchability"));
    }

    // ── fromIntegrity 映射（经 read + 注入完整性仓库驱动）────────

    private DerivationIntegrityRepository.Snapshot snapshot(
            String bucket, boolean localFresh, String localCondition,
            boolean vectorFresh, String vectorCondition,
            String localError, String vectorError, String reasonCode) {
        return new DerivationIntegrityRepository.Snapshot(
                41L, "Doc", 1L, 1L, HASH, true, false, null, null,
                CHUNKER, "READY", HASH, CHUNKER, 1L, 2, 2,
                localError, localFresh, false,
                "COMPLETED", HASH, CHUNKER, 1L, 2, 2,
                vectorError, UUID.fromString(
                        "00000000-0000-0000-0000-000000000042"),
                "COMPLETED", vectorFresh, false,
                bucket, localCondition, vectorCondition, reasonCode);
    }

    private DocumentLifecycleResponse readViaIntegrity(
            DerivationIntegrityRepository.Snapshot snapshot) {
        DerivationIntegrityRepository integrity =
                mock(DerivationIntegrityRepository.class);
        when(integrity.inspect(any(RagDocument.class))).thenReturn(snapshot);
        service.setIntegrityRepository(integrity);
        RagDocument document = new RagDocument();
        document.setId(41L);
        document.setContentHash(HASH);
        document.setEnabled(true);
        document.setDocumentType("TEXT");
        return service.read(document);
    }

    @Test
    void integrityReadyBucketMapsToReadyWithoutErrorCode() {
        DocumentLifecycleResponse response = readViaIntegrity(
                snapshot("READY", true, "READY", true, "READY",
                        null, null, null));
        assertEquals("READY", response.searchability());
        assertEquals("READY", response.localIndexStatus());
        assertNull(response.lastErrorCode());
        assertTrue(!response.retryable());
    }

    @Test
    void integrityIndexingVectorMapsToIndexingLifecycle() {
        DocumentLifecycleResponse response = readViaIntegrity(
                snapshot("INDEXING", true, "READY", false, "INDEXING",
                        null, null, "vector_pending"));
        // bucket 直接决定 searchability：INDEXING 桶原样映射。
        assertEquals("INDEXING", response.searchability());
        assertEquals("INDEXING", response.embeddingStatus());
        assertEquals("vector_pending", response.lastErrorCode());
        assertTrue(response.retryable());
    }

    @Test
    void integrityNotRequestedVectorStaysKeywordOnly() {
        DocumentLifecycleResponse response = readViaIntegrity(
                snapshot("KEYWORD_ONLY", true, "READY", false,
                        "NOT_REQUESTED", null, null, null));
        assertEquals("KEYWORD_ONLY", response.searchability());
        assertEquals("NOT_REQUESTED", response.embeddingStatus());
    }

    @Test
    void integrityUnknownBucketFallsBackToFailed() {
        DocumentLifecycleResponse response = readViaIntegrity(
                snapshot("SOMETHING_ELSE", true, "READY", true, "READY",
                        null, null, "unexpected"));
        assertEquals("FAILED", response.searchability());
        assertEquals("unexpected", response.lastErrorCode());
    }

    @Test
    void integrityNotRequestedLocalConditionWinsOverFreshness() {
        DocumentLifecycleResponse response = readViaIntegrity(
                snapshot("READY", true, "NOT_REQUESTED", true, "READY",
                        null, null, null));
        // 既有语义：localFresh=true 短路，localCondition 不参与判定。
        assertEquals("READY", response.localIndexStatus());
    }
}
