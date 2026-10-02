package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.service.EmbeddingCommitGuard;
import com.springairag.documents.chunk.TextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 嵌入持久化长尾（Batch 727，JaCoCo 驱动）：ensureContentHash 的
 * CAS 失败异常（108-111）、readDocumentSnapshot 文档缺失异常
 * （248-249）、7 参 replace 便捷重载经 allowAll 守卫完成（94-102）。
 */
class EmbeddingPersistenceServiceReplaceTailTest {

    private JdbcTemplate jdbcTemplate;
    private EmbeddingPersistenceService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new EmbeddingPersistenceService(jdbcTemplate);
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                2L, "bge-m3", "vendor", "bge-m3", "rev-1",
                1024, "cosine", "normalize", true);
    }

    @SuppressWarnings("unchecked")
    private void stubSnapshot(long version, String hash, boolean enabled) {
        when(jdbcTemplate.queryForList(
                anyString(), any(Object[].class)))
                .thenReturn((List) List.of(Map.of(
                        "version", version,
                        "content_hash", hash,
                        "enabled", enabled)));
    }

    @Test
    void ensureContentHashThrowsWhenCasMisses() {
        when(jdbcTemplate.update(
                contains("SET content_hash"),
                any(Object[].class)))
                .thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.ensureContentHash(41L, 3L, "hash-a"));
    }

    @Test
    void ensureContentHashSucceedsOnCasHit() {
        when(jdbcTemplate.update(
                contains("SET content_hash"),
                any(Object[].class)))
                .thenReturn(1);

        assertDoesNotThrow(() -> service.ensureContentHash(41L, 3L, "hash-a"));
    }

    @Test
    void replaceThrowsWhenDocumentSnapshotMissing() {
        when(jdbcTemplate.queryForList(
                anyString(), any(Object[].class)))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class,
                () -> service.replace(
                        41L, 3L, "hash-a", profile(),
                        List.of(), List.of(),
                        EmbeddingCommitGuard.allowAll()));
    }

    @Test
    void convenienceReplaceDelegatesWithAllowAllGuard() {
        stubSnapshot(3L, "hash-a", true);
        when(jdbcTemplate.update(
                anyString(), any(Object[].class)))
                .thenReturn(1);

        assertDoesNotThrow(() -> service.replace(
                41L, 3L, "hash-a", profile(),
                List.of(), List.of()));
    }

    @Test
    void replaceThrowsWhenGuardRejects() {
        EmbeddingCommitGuard guard = mock(EmbeddingCommitGuard.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("lease lost"))
                .when(guard).verify();

        assertThrows(IllegalStateException.class,
                () -> service.replace(
                        41L, 3L, "hash-a", profile(),
                        List.of(), List.of(), guard));
    }

    @Test
    void replacePersistsChunksAndState() {
        stubSnapshot(3L, "hash-a", true);
        when(jdbcTemplate.update(
                anyString(), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(
                contains("UPDATE rag_documents SET processing_status = 'COMPLETED'"),
                eq("hash-a"), eq(41L), eq(3L)))
                .thenReturn(1);
        var chunk = new TextChunk("text", 0, 4);
        var result = new EmbeddingBatchService.EmbeddingResult(
                "text", new float[]{0.1f, 0.2f}, null);

        service.replace(
                41L, 3L, "hash-a", profile(),
                List.of(chunk), List.of(result),
                EmbeddingCommitGuard.allowAll());

        org.mockito.Mockito.verify(jdbcTemplate).update(
                contains("INSERT INTO rag_embeddings"),
                eq(41L), eq("text"), eq(0), anyString(), anyString(),
                eq(2L), eq(0), eq(4));
    }
}
