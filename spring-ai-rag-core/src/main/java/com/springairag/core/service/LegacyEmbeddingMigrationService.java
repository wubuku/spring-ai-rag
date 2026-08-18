package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * 显式认领缺少模型身份的 Legacy embedding。
 */
@Service
@Profile("postgresql")
public class LegacyEmbeddingMigrationService {

    public static final String ADOPT_CONFIRMATION = "I_HAVE_VERIFIED_THE_LEGACY_MODEL";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final EmbeddingProfileRegistry profileRegistry;

    public LegacyEmbeddingMigrationService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            EmbeddingProfileRegistry profileRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.profileRegistry = profileRegistry;
    }

    public int adoptLegacy(String profileKey, String confirmation) {
        if (!ADOPT_CONFIRMATION.equals(confirmation)) {
            throw new IllegalStateException(
                    "Legacy embedding adoption requires explicit confirmation");
        }
        EmbeddingProfile profile = profileRegistry.findRequiredByKey(profileKey);
        if (profile.dimensions() != 1024) {
            throw new IllegalStateException(
                    "Legacy embedding column is VECTOR(1024), profile dimensions="
                            + profile.dimensions());
        }

        List<Long> documentIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT document_id FROM rag_embeddings "
                        + "WHERE embedding_profile_id IS NULL ORDER BY document_id",
                Long.class);
        int migrated = 0;
        for (Long documentId : documentIds) {
            Boolean completed = transactionTemplate.execute(
                    status -> adoptDocument(documentId, profile));
            if (Boolean.TRUE.equals(completed)) {
                migrated++;
            }
        }
        long remaining = countUnassigned();
        if (remaining > 0) {
            throw new IllegalStateException(
                    "Legacy embedding adoption incomplete; unassigned rows=" + remaining);
        }
        return migrated;
    }

    public long countUnassigned() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_embeddings WHERE embedding_profile_id IS NULL",
                Long.class);
        return count == null ? 0 : count;
    }

    private boolean adoptDocument(Long documentId, EmbeddingProfile profile) {
        List<Integer> indexes = jdbcTemplate.queryForList(
                "SELECT chunk_index FROM rag_embeddings "
                        + "WHERE document_id = ? AND embedding_profile_id IS NULL "
                        + "ORDER BY chunk_index",
                Integer.class,
                documentId);
        if (!hasContinuousUniqueIndexes(indexes)) {
            return false;
        }
        Integer invalidDimensions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_embeddings WHERE document_id = ? "
                        + "AND embedding_profile_id IS NULL "
                        + "AND (embedding IS NULL OR vector_dims(embedding) <> 1024)",
                Integer.class,
                documentId);
        if (invalidDimensions != null && invalidDimensions > 0) {
            return false;
        }
        Integer existingTargetRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_embeddings "
                        + "WHERE document_id = ? AND embedding_profile_id = ?",
                Integer.class,
                documentId,
                profile.id());
        if (existingTargetRows != null && existingTargetRows > 0) {
            return false;
        }

        Map<String, Object> document = jdbcTemplate.queryForMap(
                "SELECT content, version, content_hash "
                        + "FROM rag_documents WHERE id = ?",
                documentId);
        String content = (String) document.get("content");
        long expectedVersion = ((Number) document.get("version")).longValue();
        String contentHash = (String) document.get("content_hash");
        if (contentHash == null || contentHash.isBlank()) {
            contentHash = BatchDocumentService.computeSha256(content);
            int initialized = jdbcTemplate.update(
                    "UPDATE rag_documents SET content_hash = ?, version = version + 1, "
                            + "updated_at = NOW() WHERE id = ? AND version = ? "
                            + "AND (content_hash IS NULL OR content_hash = '')",
                    contentHash, documentId, expectedVersion);
            if (initialized != 1) {
                return false;
            }
            expectedVersion++;
        }

        int adopted = jdbcTemplate.update(
                "UPDATE rag_embeddings SET embedding_1024 = embedding, "
                        + "embedding_profile_id = ? "
                        + "WHERE document_id = ? AND embedding_profile_id IS NULL",
                profile.id(),
                documentId);
        if (adopted != indexes.size()) {
            return false;
        }
        jdbcTemplate.update(
                "INSERT INTO rag_document_embedding_state "
                        + "(document_id, embedding_profile_id, content_hash, chunker_version, "
                        + "status, chunk_count, completed_at, updated_at) "
                        + "VALUES (?, ?, ?, 'legacy-adopted-unknown', 'COMPLETED', ?, NOW(), NOW()) "
                        + "ON CONFLICT (document_id, embedding_profile_id) DO UPDATE SET "
                        + "content_hash = EXCLUDED.content_hash, "
                        + "chunker_version = EXCLUDED.chunker_version, "
                        + "status = 'COMPLETED', chunk_count = EXCLUDED.chunk_count, "
                        + "processing_error = NULL, completed_at = NOW(), updated_at = NOW()",
                documentId,
                profile.id(),
                contentHash,
                indexes.size());
        int fenced = jdbcTemplate.update(
                "UPDATE rag_documents SET version = version + 1, updated_at = NOW() "
                        + "WHERE id = ? AND version = ?",
                documentId, expectedVersion);
        if (fenced != 1) {
            throw new IllegalStateException(
                    "Document changed during legacy embedding adoption: " + documentId);
        }
        return true;
    }

    private boolean hasContinuousUniqueIndexes(List<Integer> indexes) {
        if (indexes.isEmpty()) {
            return false;
        }
        for (int i = 0; i < indexes.size(); i++) {
            if (indexes.get(i) == null || indexes.get(i) != i) {
                return false;
            }
        }
        return true;
    }
}
