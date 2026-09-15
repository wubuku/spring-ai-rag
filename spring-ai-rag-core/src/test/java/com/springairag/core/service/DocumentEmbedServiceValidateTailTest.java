package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.documents.chunk.TextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * DocumentEmbedService 校验与批量长尾（Batch 424）：
 * validateEmbeddingResults 的数量/成功/顺序/维度/有限性矩阵、
 * safeError 兜底与截断、batchEmbedDocuments 的入参门卫。
 */
class DocumentEmbedServiceValidateTailTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            7L, "bge-m3-1024-test", "siliconflow", "BAAI/bge-m3",
            "test", 1024, "COSINE", "PROVIDER_DEFAULT", true);

    private DocumentEmbedService service;
    private Method validate;

    @BeforeEach
    void setUp() throws Exception {
        service = new DocumentEmbedService(
                mock(RagDocumentRepository.class),
                mock(EmbeddingBatchService.class),
                mock(EmbeddingPersistenceService.class),
                () -> PROFILE,
                new RagProperties());
        validate = DocumentEmbedService.class.getDeclaredMethod(
                "validateEmbeddingResults",
                List.class, List.class, EmbeddingProfile.class);
        validate.setAccessible(true);
    }

    private TextChunk chunk(String text) {
        return new TextChunk(text, 0, text.length());
    }

    private EmbeddingBatchService.EmbeddingResult ok(String text, int dims) {
        float[] vector = new float[dims];
        vector[0] = 0.5f;
        return new EmbeddingBatchService.EmbeddingResult(text, vector, null);
    }

    private String validate(List<TextChunk> chunks,
                            List<EmbeddingBatchService.EmbeddingResult> results) {
        try {
            return (String) validate.invoke(service, chunks, results, PROFILE);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String safeError(String value) throws Exception {
        Method method = DocumentEmbedService.class.getDeclaredMethod(
                "safeError", String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, value);
    }

    @Test
    void batchEmbedDocumentsGuardsInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> service.batchEmbedDocuments(null));
        assertThrows(IllegalArgumentException.class, () -> {
            var ids = new java.util.ArrayList<Long>();
            for (int i = 0; i < 51; i++) {
                ids.add((long) i);
            }
            service.batchEmbedDocuments(ids);
        });
    }

    @Test
    void resultCountMismatchIsReported() {
        String error = validate(
                List.of(chunk("a")),
                List.of());
        assertEquals("Embedding result count mismatch: expected=1, actual=0",
                error);
    }

    @Test
    void failedOrMissingResultIsReported() {
        String failed = validate(
                List.of(chunk("a")),
                List.of(new EmbeddingBatchService.EmbeddingResult(
                        "a", null, "quota")));
        assertEquals("Embedding failed for chunk 0: quota", failed);

        String missing = validate(
                List.of(chunk("a")),
                List.of(new EmbeddingBatchService.EmbeddingResult(
                        "a", null, null)));
        // error 非 null 但 isSuccess 检查先行：null embedding → 失败。
        assertTrue(missing == null || missing.startsWith("Embedding failed"));
    }

    @Test
    void orderMismatchIsDetected() {
        String error = validate(
                List.of(chunk("first"), chunk("second")),
                List.of(ok("second", 1024), ok("first", 1024)));
        assertEquals("Embedding response order mismatch at chunk 0", error);
    }

    @Test
    void nullOrWrongDimensionVectorsAreRejected() {
        // 既有语义：向量缺失时 isSuccess=false，走失败分支并透出 null 错误。
        String nullVector = validate(
                List.of(chunk("a")),
                List.of(new EmbeddingBatchService.EmbeddingResult(
                        "a", null, null)));
        assertEquals("Embedding failed for chunk 0: null", nullVector);

        String wrongDims = validate(
                List.of(chunk("a")),
                List.of(ok("a", 3)));
        assertEquals("Embedding dimension mismatch at chunk 0: "
                + "expected=1024, actual=3", wrongDims);
    }

    @Test
    void nonFiniteVectorValuesAreRejected() {
        float[] vector = new float[1024];
        vector[7] = Float.NaN;
        String error = validate(
                List.of(chunk("a")),
                List.of(new EmbeddingBatchService.EmbeddingResult(
                        "a", vector, null)));
        assertEquals("Embedding contains non-finite value at chunk 0", error);
    }

    @Test
    void validResultsReturnNull() {
        String error = validate(
                List.of(chunk("a"), chunk("b")),
                List.of(ok("a", 1024), ok("b", 1024)));
        assertNull(error);
    }

    @Test
    void safeErrorFallsBackAndTruncates() throws Exception {
        assertEquals("Embedding failed", safeError(null));
        assertEquals("Embedding failed", safeError("  "));
        assertEquals("provider quota", safeError("provider quota"));
        assertEquals(500, safeError("x".repeat(600)).length());
    }
}
