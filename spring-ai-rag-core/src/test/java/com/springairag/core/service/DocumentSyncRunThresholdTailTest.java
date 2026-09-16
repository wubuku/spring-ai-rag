package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentSyncRunCompleteRequest;
import com.springairag.api.enums.DocumentSyncMissingPolicy;
import com.springairag.api.enums.DocumentSyncSnapshotMode;
import com.springairag.api.enums.DocumentSyncRunStatus;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentSyncRunService 墓碑完成阈值长尾（Batch 450）：
 * requireMissingCountWithinThreshold 的确认数/阈值矩阵、
 * reconcileMissingCandidates 的策略守卫与墓碑计数。
 */
class DocumentSyncRunThresholdTailTest {

    private JdbcTemplate jdbcTemplate;
    private DocumentMutationService mutationService;
    private DocumentSyncRunService service;
    private RagProperties ragProperties;
    private UUID runId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        jdbcTemplate = mock(JdbcTemplate.class);
        mutationService = mock(DocumentMutationService.class);
        ragProperties = new RagProperties();
        ragProperties.getDocumentLifecycle().setSyncRunsEnabled(true);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new DocumentSyncRunService(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules(),
                mock(CollectionIdentityResolver.class),
                mutationService,
                mock(DocumentSyncRunItemReceiptRepository.class),
                ragProperties,
                transactionManager);
        runId = UUID.randomUUID();
    }

    private Object runRow(DocumentSyncMissingPolicy policy,
                          String previewFingerprint,
                          String previewTokenHash) throws Exception {
        Class<?> runRowClass = Class.forName(
                "com.springairag.core.service.DocumentSyncRunService$RunRow");
        var ctor = runRowClass.getDeclaredConstructor(
                UUID.class, long.class, String.class, String.class,
                String.class, long.class, long.class, Long.class,
                DocumentSyncSnapshotMode.class,
                DocumentSyncMissingPolicy.class,
                DocumentSyncRunStatus.class, OffsetDateTime.class,
                String.class, String.class, Integer.class,
                int.class, int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                runId, 7L, "default", "client-run-1",
                com.springairag.core.util.DigestUtils.sha256("lease-1"),
                1L, 0L, null,
                DocumentSyncSnapshotMode.ONLINE_CUT,
                policy,
                DocumentSyncRunStatus.ACTIVE,
                OffsetDateTime.now().plusMinutes(10),
                previewTokenHash, previewFingerprint, null,
                0, 0, 0, 0, 0);
    }

    private Object candidate(long documentId, String externalId) throws Exception {
        Class<?> candidateClass = Class.forName(
                "com.springairag.core.service.DocumentSyncRunService$Candidate");
        var ctor = candidateClass.getDeclaredConstructor(
                long.class, String.class,
                com.springairag.api.enums.DocumentSyncDocumentKind.class,
                String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(documentId, externalId,
                com.springairag.api.enums.DocumentSyncDocumentKind.TEXT,
                "rev-1");
    }

    private Object candidateSet(Object... candidates) throws Exception {
        Class<?> setClass = Class.forName(
                "com.springairag.core.service.DocumentSyncRunService$CandidateSet");
        var ctor = setClass.getDeclaredConstructor(List.class,
                int.class, int.class, int.class, int.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(List.of(candidates),
                candidates.length, 0, 0, 0, "fp-1");
    }

    private Object invokeThreshold(Object run, Object candidateSet,
                                   Integer confirmMissingCount) throws Exception {
        Method method = DocumentSyncRunService.class.getDeclaredMethod(
                "requireMissingCountWithinThreshold", Class.forName(
                        "com.springairag.core.service.DocumentSyncRunService$RunRow"),
                Class.forName(
                        "com.springairag.core.service.DocumentSyncRunService$CandidateSet"),
                DocumentSyncRunCompleteRequest.class);
        method.setAccessible(true);
        try {
            method.invoke(service, run, candidateSet,
                    new DocumentSyncRunCompleteRequest("token", confirmMissingCount));
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            return e.getCause();
        }
    }

    private Object invokeReconcile(Object run, Object candidateSet,
                                   Integer confirmMissingCount) throws Exception {
        Method method = DocumentSyncRunService.class.getDeclaredMethod(
                "reconcileMissingCandidates", Class.forName(
                        "com.springairag.core.service.DocumentSyncRunService$RunRow"),
                Class.forName(
                        "com.springairag.core.service.DocumentSyncRunService$CandidateSet"),
                DocumentSyncRunCompleteRequest.class);
        method.setAccessible(true);
        try {
            return method.invoke(service, run, candidateSet,
                    new DocumentSyncRunCompleteRequest("token", confirmMissingCount));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void nonTombstonePolicySkipsThresholdEntirely() throws Exception {
        Object run = runRow(DocumentSyncMissingPolicy.NONE, "fp-1", "token-hash");
        Object candidates = candidateSet(candidate(1L, "e1"), candidate(2L, "e2"));

        // NONE 策略不做阈值校验，直接返回 0（无墓碑）。
        assertEquals(0, invokeReconcile(run, candidates, null));
    }

    @Test
    void confirmedCountMustMatchPreviewedCandidateCount() throws Exception {
        Object run = runRow(DocumentSyncMissingPolicy.TOMBSTONE, "fp-1", "token-hash");
        Object candidates = candidateSet(candidate(1L, "e1"), candidate(2L, "e2"));
        // activeCount=10, percent=20 → threshold=2。
        when(jdbcTemplate.queryForObject(
                contains("COUNT(*) FROM rag_documents"), eq(Long.class),
                eq(7L), eq("default"))).thenReturn(10L);

        Object error = invokeThreshold(run, candidates, 1);
        assertEquals(RagException.class, error.getClass());
        assertTrue(((RagException) error).getMessage()
                .contains("confirmMissingCount must equal"));

        // 确认数与预览数一致 → 通过（不抛）。
        assertEquals(null, invokeThreshold(run, candidates, 2));
    }

    @Test
    void exceedingThresholdWithoutConfirmationIsRejected() throws Exception {
        Object run = runRow(DocumentSyncMissingPolicy.TOMBSTONE, "fp-1", "token-hash");
        Object candidates = candidateSet(candidate(1L, "e1"), candidate(2L, "e2"),
                candidate(3L, "e3"));
        when(jdbcTemplate.queryForObject(
                contains("COUNT(*) FROM rag_documents"), eq(Long.class),
                eq(7L), eq("default"))).thenReturn(10L);

        // 3 个候选 > threshold 2 且未确认 → 拒绝。
        Object error = invokeThreshold(run, candidates, null);
        assertEquals(RagException.class, error.getClass());
        assertTrue(((RagException) error).getMessage()
                .contains("Missing count exceeds"));
    }

    @Test
    void reconcileCountsTombstonedCandidates() throws Exception {
        Object run = runRow(DocumentSyncMissingPolicy.TOMBSTONE, "fp-1", "token-hash");
        Object candidates = candidateSet(candidate(1L, "e1"), candidate(2L, "e2"));
        when(jdbcTemplate.queryForObject(
                contains("COUNT(*) FROM rag_documents"), eq(Long.class),
                eq(7L), eq("default"))).thenReturn(10L);
        when(mutationService.reconcileMissingExternal(
                eq(1L), eq(runId), anyLong())).thenReturn(true);
        when(mutationService.reconcileMissingExternal(
                eq(2L), eq(runId), anyLong())).thenReturn(false);

        assertEquals(1, invokeReconcile(run, candidates, 2));
    }

    @Test
    void confirmCountEqualsZeroWithEmptyCandidatesPasses() throws Exception {
        Object run = runRow(DocumentSyncMissingPolicy.TOMBSTONE, "fp-1", "token-hash");
        when(jdbcTemplate.queryForObject(
                contains("COUNT(*) FROM rag_documents"), eq(Long.class),
                eq(7L), eq("default"))).thenReturn(0L);

        Object error = invokeThreshold(run, candidateSet(), 0);
        assertEquals(null, error);
    }
}
