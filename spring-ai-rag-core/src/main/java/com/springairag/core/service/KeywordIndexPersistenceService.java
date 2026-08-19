package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import com.springairag.core.util.DigestUtils;
import com.springairag.documents.chunk.TextChunk;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地关键词索引的持久化协调器。
 *
 * <p>本组件只管理 document-local chunks，不读取或写入任何 embedding
 * profile。generation/CAS 和唯一约束用于跨实例收敛，不使用显式悲观锁。</p>
 */
@Service
public class KeywordIndexPersistenceService {

    private static final int MAX_ERROR_LENGTH = 500;

    private final JdbcTemplate jdbcTemplate;
    private final DocumentChunkingService chunkingService;
    private final DocumentDerivationDescriptorProvider descriptorProvider;

    public KeywordIndexPersistenceService(
            JdbcTemplate jdbcTemplate,
            DocumentChunkingService chunkingService,
            DocumentDerivationDescriptorProvider descriptorProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.chunkingService = chunkingService;
        this.descriptorProvider = descriptorProvider;
    }

    /**
     * 确保当前 document content 的本地 chunk 已可被全文检索。
     */
    @Transactional
    public void ensureCurrent(RagDocument document) {
        if (document == null || document.getId() == null) {
            throw new IllegalArgumentException("Document identity is required");
        }
        if (!Boolean.TRUE.equals(document.getEnabled())) {
            throw new IllegalStateException(
                    "Disabled document cannot prepare a local keyword index: "
                            + document.getId());
        }
        String contentHash = ensureContentHash(document);
        DocumentChunkingService.PreparedChunks prepared =
                chunkingService.prepare(document);
        if (isCurrent(document.getId(), contentHash,
                prepared.descriptor().chunkerVersion())) {
            return;
        }

        long generation = allocateGeneration(
                document.getId(), contentHash,
                prepared.descriptor().chunkerVersion(), true);
        validateChunks(document, prepared.chunks());

        jdbcTemplate.update(
                "DELETE FROM rag_document_chunks WHERE document_id = ?",
                document.getId());
        AtomicInteger chunkIndex = new AtomicInteger();
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO rag_document_chunks (
                    document_id, local_index_generation, content_hash,
                    chunker_version, chunk_text, chunk_index,
                    chunk_start_pos, chunk_end_pos, metadata, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                prepared.chunks(),
                100,
                (PreparedStatement ps, TextChunk chunk) -> {
                    int index = chunkIndex.getAndIncrement();
                    ps.setLong(1, document.getId());
                    ps.setLong(2, generation);
                    ps.setString(3, contentHash);
                    ps.setString(4, prepared.descriptor().chunkerVersion());
                    ps.setString(5, chunk.text());
                    ps.setInt(6, index);
                    ps.setInt(7, chunk.startPos());
                    ps.setInt(8, chunk.endPos());
                    ps.setObject(9, null);
                });

        int updated = jdbcTemplate.update(
                """
                UPDATE rag_document_local_index_state
                SET local_index_status = 'READY',
                    content_hash = ?,
                    chunker_version = ?,
                    local_index_generation = ?,
                    chunk_count = ?,
                    processing_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ?
                  AND local_index_generation = ?
                  AND EXISTS (
                      SELECT 1
                      FROM rag_documents document
                      WHERE document.id = ?
                        AND document.enabled = true
                        AND document.content_hash = ?
                  )
                """,
                contentHash,
                prepared.descriptor().chunkerVersion(),
                generation,
                prepared.chunks().size(),
                document.getId(),
                generation,
                document.getId(),
                contentHash);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Document changed while preparing local keyword index: "
                            + document.getId());
        }
    }

    /**
     * 明确表示调用者选择 SKIP：旧 local chunks 必须立即退出。
     */
    @Transactional
    public void markNotRequested(RagDocument document) {
        if (document == null || document.getId() == null) {
            throw new IllegalArgumentException("Document identity is required");
        }
        String hash = document.getContentHash();
        String chunkerVersion = descriptorProvider.describe(document)
                .chunkerVersion();
        long generation = allocateGeneration(
                document.getId(), hash, chunkerVersion, false);
        jdbcTemplate.update(
                "DELETE FROM rag_document_chunks WHERE document_id = ?",
                document.getId());
        int updated = jdbcTemplate.update(
                """
                UPDATE rag_document_local_index_state
                SET local_index_status = 'NOT_REQUESTED',
                    content_hash = ?,
                    chunker_version = ?,
                    local_index_generation = ?,
                    chunk_count = 0,
                    processing_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND local_index_generation = ?
                """,
                hash,
                chunkerVersion,
                generation,
                document.getId(),
                generation);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Local keyword index state changed while skipping document: "
                            + document.getId());
        }
    }

    public boolean hasFreshLocalIndex(RagDocument document) {
        if (document == null || document.getId() == null
                || document.getContentHash() == null
                || document.getContentHash().isBlank()) {
            return false;
        }
        try {
            String chunkerVersion = descriptorProvider.describe(document)
                    .chunkerVersion();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    """
                    SELECT local_index_generation, chunk_count
                    FROM rag_document_local_index_state
                    WHERE document_id = ?
                      AND local_index_status = 'READY'
                      AND content_hash = ?
                      AND chunker_version = ?
                      AND chunk_count > 0
                    """,
                    document.getId(), document.getContentHash(), chunkerVersion);
            if (rows.isEmpty()) {
                return false;
            }
            Map<String, Object> row = rows.getFirst();
            long generation = ((Number) row.get(
                    "local_index_generation")).longValue();
            int chunkCount = ((Number) row.get("chunk_count")).intValue();
            Long actualCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM rag_document_chunks
                    WHERE document_id = ?
                      AND local_index_generation = ?
                      AND content_hash = ?
                      AND chunker_version = ?
                    """,
                    Long.class,
                    document.getId(), generation,
                    document.getContentHash(), chunkerVersion);
            return actualCount != null && actualCount == chunkCount;
        } catch (DataAccessException e) {
            return false;
        }
    }

    public String ensureContentHash(RagDocument document) {
        String current = document.getContentHash();
        if (current != null && !current.isBlank()) {
            return current;
        }
        String calculated = DigestUtils.sha256(document.getContent());
        long expectedVersion = document.getVersion() == null
                ? 0L : document.getVersion();
        int updated = jdbcTemplate.update(
                """
                UPDATE rag_documents
                SET content_hash = ?, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND version = ?
                  AND (content_hash IS NULL OR content_hash = '')
                """,
                calculated, document.getId(), expectedVersion);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Document changed while initializing content hash: "
                            + document.getId());
        }
        document.setContentHash(calculated);
        document.setVersion(expectedVersion + 1);
        return calculated;
    }

    private long allocateGeneration(
            long documentId,
            String contentHash,
            String chunkerVersion,
            boolean requireEnabled) {
        String enabledPredicate = requireEnabled
                ? " AND document.enabled = true"
                : "";
        List<Long> updated = jdbcTemplate.query(
                """
                UPDATE rag_document_local_index_state state
                SET local_index_generation = state.local_index_generation + 1,
                    local_index_status = 'FAILED',
                    content_hash = ?,
                    chunker_version = ?,
                    chunk_count = 0,
                    processing_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE state.document_id = ?
                  AND EXISTS (
                      SELECT 1
                      FROM rag_documents document
                      WHERE document.id = state.document_id
                        AND document.content_hash IS NOT DISTINCT FROM ?
                """
                        + enabledPredicate
                        + """
                  )
                RETURNING state.local_index_generation
                """,
                ps -> {
                    ps.setString(1, contentHash);
                    ps.setString(2, chunkerVersion);
                    ps.setLong(3, documentId);
                    ps.setString(4, contentHash);
                },
                (rs, rowNum) -> rs.getLong(1));
        if (!updated.isEmpty()) {
            return updated.getFirst();
        }

        jdbcTemplate.update(
                """
                INSERT INTO rag_document_local_index_state (
                    document_id, local_index_status, content_hash,
                    chunker_version, local_index_generation, chunk_count,
                    updated_at
                )
                SELECT document.id, 'FAILED', ?, ?, 0, 0, CURRENT_TIMESTAMP
                FROM rag_documents document
                WHERE document.id = ?
                  AND document.content_hash IS NOT DISTINCT FROM ?
                """
                        + enabledPredicate
                        + "\nON CONFLICT (document_id) DO NOTHING\n",
                contentHash, chunkerVersion, documentId, contentHash);

        updated = jdbcTemplate.query(
                """
                UPDATE rag_document_local_index_state state
                SET local_index_generation = state.local_index_generation + 1,
                    local_index_status = 'FAILED',
                    content_hash = ?,
                    chunker_version = ?,
                    chunk_count = 0,
                    processing_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE state.document_id = ?
                  AND EXISTS (
                      SELECT 1
                      FROM rag_documents document
                      WHERE document.id = state.document_id
                        AND document.content_hash IS NOT DISTINCT FROM ?
                """
                        + enabledPredicate
                        + """
                  )
                RETURNING state.local_index_generation
                """,
                ps -> {
                    ps.setString(1, contentHash);
                    ps.setString(2, chunkerVersion);
                    ps.setLong(3, documentId);
                    ps.setString(4, contentHash);
                },
                (rs, rowNum) -> rs.getLong(1));
        if (updated.isEmpty()) {
            throw new IllegalStateException(
                    "Document changed while allocating local index generation: "
                            + documentId);
        }
        return updated.getFirst();
    }

    private boolean isCurrent(
            long documentId, String contentHash, String chunkerVersion) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    """
                    SELECT local_index_generation, chunk_count
                    FROM rag_document_local_index_state
                    WHERE document_id = ?
                      AND local_index_status = 'READY'
                      AND content_hash = ?
                      AND chunker_version = ?
                      AND chunk_count > 0
                    """,
                    documentId, contentHash, chunkerVersion);
            if (rows.isEmpty()) {
                return false;
            }
            Map<String, Object> row = rows.getFirst();
            Long count = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM rag_document_chunks
                    WHERE document_id = ?
                      AND local_index_generation = ?
                      AND content_hash = ?
                      AND chunker_version = ?
                    """,
                    Long.class,
                    documentId,
                    ((Number) row.get("local_index_generation")).longValue(),
                    contentHash,
                    chunkerVersion);
            return count != null
                    && count.intValue() == ((Number) row.get("chunk_count")).intValue();
        } catch (DataAccessException e) {
            return false;
        }
    }

    private void validateChunks(
            RagDocument document, List<TextChunk> chunks) {
        String content = document.getContent();
        int expectedIndex = 0;
        for (TextChunk chunk : chunks) {
            if (chunk == null || chunk.text() == null
                    || chunk.text().isBlank()) {
                throw new IllegalArgumentException("Chunk text must not be blank");
            }
            if (chunk.startPos() < 0
                    || chunk.endPos() < chunk.startPos()
                    || chunk.endPos() > content.length()) {
                throw new IllegalArgumentException(
                        "Chunk position is outside document content: documentId="
                                + document.getId());
            }
            expectedIndex++;
        }
        if (expectedIndex == 0) {
            throw new IllegalArgumentException("Document produced no chunks");
        }
    }

    public String sanitizeError(String error) {
        if (error == null || error.isBlank()) {
            return "Local keyword index failed";
        }
        String masked = SensitiveDataMaskingConverter.maskSensitiveData(error);
        return masked.length() <= MAX_ERROR_LENGTH
                ? masked : masked.substring(0, MAX_ERROR_LENGTH);
    }
}
