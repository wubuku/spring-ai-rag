package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingVectorColumns;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.retrieval.RetrievalUtils;
import com.springairag.documents.chunk.TextChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Profile 级 embedding 缓存、失败状态和原子替换。
 */
@Service
public class EmbeddingPersistenceService {

    private static final int MAX_ERROR_LENGTH = 500;

    private final JdbcTemplate jdbcTemplate;

    public EmbeddingPersistenceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public CacheState findCacheState(
            long documentId,
            EmbeddingProfile profile,
            String contentHash,
            String chunkerVersion) {
        String column = EmbeddingVectorColumns.columnFor(profile.dimensions());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, content_hash, chunker_version, chunk_count "
                        + "FROM rag_document_embedding_state "
                        + "WHERE document_id = ? AND embedding_profile_id = ?",
                documentId,
                profile.id());
        if (rows.isEmpty()) {
            return CacheState.miss();
        }
        Map<String, Object> row = rows.getFirst();
        int chunkCount = ((Number) row.get("chunk_count")).intValue();
        boolean metadataMatches = "COMPLETED".equals(row.get("status"))
                && contentHash.equals(row.get("content_hash"))
                && chunkerVersion.equals(row.get("chunker_version"))
                && chunkCount > 0;
        if (!metadataMatches) {
            return CacheState.miss();
        }
        Long actualCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_embeddings "
                        + "WHERE document_id = ? AND embedding_profile_id = ? "
                        + "AND " + column + " IS NOT NULL",
                Long.class,
                documentId,
                profile.id());
        return actualCount != null && actualCount == chunkCount
                ? CacheState.hit(chunkCount)
                : CacheState.miss();
    }

    @Transactional
    public void ensureContentHash(long documentId, long expectedVersion, String contentHash) {
        int updated = jdbcTemplate.update(
                "UPDATE rag_documents SET content_hash = ?, version = version + 1, "
                        + "updated_at = NOW() WHERE id = ? AND version = ? "
                        + "AND (content_hash IS NULL OR content_hash = '')",
                contentHash,
                documentId,
                expectedVersion);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Document changed while initializing content hash: " + documentId);
        }
    }

    @Transactional
    public void replace(
            long documentId,
            long expectedVersion,
            String expectedContentHash,
            EmbeddingProfile profile,
            String chunkerVersion,
            List<TextChunk> chunks,
            List<EmbeddingBatchService.EmbeddingResult> results) {
        Map<String, Object> document = lockDocument(documentId);
        long actualVersion = ((Number) document.get("version")).longValue();
        String actualHash = (String) document.get("content_hash");
        boolean enabled = Boolean.TRUE.equals(document.get("enabled"));
        if (actualVersion != expectedVersion
                || !expectedContentHash.equals(actualHash)
                || !enabled) {
            throw new IllegalStateException(
                    "Document changed while embeddings were generated: " + documentId);
        }

        String column = EmbeddingVectorColumns.columnFor(profile.dimensions());
        jdbcTemplate.update(
                "DELETE FROM rag_embeddings WHERE document_id = ? AND embedding_profile_id = ?",
                documentId,
                profile.id());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            String vector = RetrievalUtils.vectorToString(results.get(i).getEmbedding());
            String sql = "INSERT INTO rag_embeddings "
                    + "(document_id, chunk_text, chunk_index, embedding, " + column + ", "
                    + "embedding_profile_id, chunk_start_pos, chunk_end_pos, created_at) "
                    + "VALUES (?, ?, ?, ?::vector, ?::vector, ?, ?, ?, NOW())";
            jdbcTemplate.update(
                    sql,
                    documentId,
                    chunk.text(),
                    i,
                    vector,
                    vector,
                    profile.id(),
                    chunk.startPos(),
                    chunk.endPos());
        }

        jdbcTemplate.update(
                "INSERT INTO rag_document_embedding_state "
                        + "(document_id, embedding_profile_id, content_hash, chunker_version, "
                        + "status, chunk_count, processing_error, completed_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', ?, NULL, NOW(), NOW()) "
                        + "ON CONFLICT (document_id, embedding_profile_id) DO UPDATE SET "
                        + "content_hash = EXCLUDED.content_hash, "
                        + "chunker_version = EXCLUDED.chunker_version, "
                        + "status = 'COMPLETED', chunk_count = EXCLUDED.chunk_count, "
                        + "processing_error = NULL, completed_at = NOW(), updated_at = NOW()",
                documentId,
                profile.id(),
                expectedContentHash,
                chunkerVersion,
                chunks.size());
        int updated = jdbcTemplate.update(
                "UPDATE rag_documents SET processing_status = 'COMPLETED', "
                        + "processing_error = NULL, embedded_content_hash = ?, "
                        + "version = version + 1, updated_at = NOW() WHERE id = ? AND version = ?",
                expectedContentHash,
                documentId,
                expectedVersion);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Document changed during embedding commit: " + documentId);
        }
    }

    @Transactional
    public void recordFailureIfNoCompleted(
            long documentId,
            long expectedVersion,
            String expectedContentHash,
            EmbeddingProfile profile,
            String chunkerVersion,
            String error) {
        String safeError = sanitizeError(error);
        Map<String, Object> document = lockDocument(documentId);
        long actualVersion = ((Number) document.get("version")).longValue();
        String actualHash = (String) document.get("content_hash");
        if (actualVersion != expectedVersion || !expectedContentHash.equals(actualHash)) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO rag_document_embedding_state "
                        + "(document_id, embedding_profile_id, content_hash, chunker_version, "
                        + "status, chunk_count, processing_error, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'FAILED', 0, ?, NOW()) "
                        + "ON CONFLICT (document_id, embedding_profile_id) DO UPDATE SET "
                        + "content_hash = EXCLUDED.content_hash, "
                        + "chunker_version = EXCLUDED.chunker_version, status = 'FAILED', "
                        + "chunk_count = 0, processing_error = EXCLUDED.processing_error, "
                        + "completed_at = NULL, updated_at = NOW()",
                documentId,
                profile.id(),
                expectedContentHash,
                chunkerVersion,
                safeError);
        int updated = jdbcTemplate.update(
                "UPDATE rag_documents SET processing_status = 'FAILED', processing_error = ?, "
                        + "version = version + 1, updated_at = NOW() WHERE id = ? AND version = ?",
                safeError,
                documentId,
                expectedVersion);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Document changed during embedding failure commit: " + documentId);
        }
    }

    private String sanitizeError(String error) {
        if (error == null || error.isBlank()) {
            return "Embedding failed";
        }
        String masked = SensitiveDataMaskingConverter.maskSensitiveData(error);
        return masked.length() <= MAX_ERROR_LENGTH
                ? masked : masked.substring(0, MAX_ERROR_LENGTH);
    }

    private Map<String, Object> lockDocument(long documentId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version, content_hash, enabled FROM rag_documents "
                        + "WHERE id = ? FOR UPDATE",
                documentId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Document not found during embedding commit: " + documentId);
        }
        return rows.getFirst();
    }

    public record CacheState(boolean hit, int chunkCount) {
        static CacheState hit(int chunkCount) {
            return new CacheState(true, chunkCount);
        }

        static CacheState miss() {
            return new CacheState(false, 0);
        }
    }
}
