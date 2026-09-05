package com.springairag.core.service;

import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将文档、本地关键词派生和活动 Profile 状态归一为公开生命周期读模型。
 */
@Service
public class DocumentLifecycleService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProfileProvider profileProvider;
    private final DocumentDerivationDescriptorProvider descriptorProvider;
    private DerivationIntegrityRepository integrityRepository;

    public DocumentLifecycleService(
            JdbcTemplate jdbcTemplate,
            EmbeddingProfileProvider profileProvider,
            DocumentDerivationDescriptorProvider descriptorProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.profileProvider = profileProvider;
        this.descriptorProvider = descriptorProvider;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setIntegrityRepository(DerivationIntegrityRepository integrityRepository) {
        this.integrityRepository = integrityRepository;
    }

    public DocumentLifecycleResponse read(RagDocument document) {
        if (!Boolean.TRUE.equals(document.getEnabled())) {
            return new DocumentLifecycleResponse(
                    document.getSourceDeletedAt() != null
                            ? "TOMBSTONED" : "DISABLED",
                    "DISABLED",
                    "DISABLED",
                    "DISABLED",
                    activeProfileKey(),
                    null,
                    null,
                    null,
                    false);
        }

        if (integrityRepository != null) {
            return fromIntegrity(integrityRepository.inspect(document));
        }

        EmbeddingProfile profile = profileProvider.getActiveProfile();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT local.local_index_status,
                       local.content_hash AS local_content_hash,
                       local.chunker_version AS local_chunker_version,
                       local.local_index_generation,
                       local.chunk_count AS local_chunk_count,
                       (
                           SELECT COUNT(*)
                           FROM rag_document_chunks local_chunk
                           WHERE local_chunk.document_id = local.document_id
                             AND local_chunk.local_index_generation =
                                 local.local_index_generation
                             AND local_chunk.content_hash = local.content_hash
                             AND local_chunk.chunker_version =
                                 local.chunker_version
                       ) AS local_actual_chunk_count,
                       local.processing_error AS local_error,
                       embedding.status AS embedding_status,
                       embedding.content_hash AS embedding_content_hash,
                       embedding.chunker_version AS embedding_chunker_version,
                       embedding.chunk_count AS embedding_chunk_count,
                       embedding.active_job_id,
                       embedding.processing_error AS embedding_error,
                       job.status AS job_status,
                       job.last_error AS job_error
                FROM rag_documents document
                LEFT JOIN rag_document_local_index_state local
                  ON local.document_id = document.id
                LEFT JOIN rag_document_embedding_state embedding
                  ON embedding.document_id = document.id
                 AND embedding.embedding_profile_id = ?
                LEFT JOIN rag_embedding_jobs job
                  ON job.id = embedding.active_job_id
                WHERE document.id = ?
                """,
                profile.id(), document.getId());
        if (rows.isEmpty()) {
            return notRequested(profile.profileKey(), null, null);
        }

        Map<String, Object> row = rows.getFirst();
        String expectedChunker = descriptorProvider.describe(document)
                .chunkerVersion();
        DerivedLifecycle derived = deriveFromStateRow(
                row, document.getContentHash(), expectedChunker);

        return new DocumentLifecycleResponse(
                "ACTIVE",
                derived.searchability(),
                derived.localStatus(),
                derived.embeddingStatus(),
                profile.profileKey(),
                uuid(row.get("active_job_id")),
                derived.errorCode(),
                derived.error(),
                !"READY".equals(derived.searchability()));
    }

    private record DerivedLifecycle(
            String localStatus,
            String embeddingStatus,
            String searchability,
            String error,
            String errorCode) {
    }

    /** 从状态行推导公开生命周期（纯函数，便于单测覆盖状态矩阵）。 */
    private static DerivedLifecycle deriveFromStateRow(
            Map<String, Object> row,
            String contentHash,
            String expectedChunker) {
        String localState = string(row.get("local_index_status"));
        String embeddingState = string(row.get("embedding_status"));
        boolean localPresent = localState != null;
        boolean embeddingPresent = embeddingState != null;
        boolean localCurrent = localPresent
                && "READY".equals(localState)
                && equals(contentHash, row.get("local_content_hash"))
                && equals(expectedChunker, row.get("local_chunker_version"))
                && positive(row.get("local_index_generation"))
                && positive(row.get("local_chunk_count"))
                && positive(row.get("local_actual_chunk_count"))
                && ((Number) row.get("local_actual_chunk_count")).intValue()
                        == ((Number) row.get("local_chunk_count")).intValue();
        boolean embeddingCurrent = embeddingPresent
                && "COMPLETED".equals(embeddingState)
                && equals(contentHash, row.get("embedding_content_hash"))
                && equals(expectedChunker, row.get("embedding_chunker_version"))
                && positive(row.get("embedding_chunk_count"));

        String localStatus = deriveLocalStatus(
                localState, localPresent, localCurrent, embeddingPresent);
        String embeddingStatus = deriveEmbeddingStatus(
                embeddingState, embeddingPresent, embeddingCurrent, row);
        String searchability = deriveSearchability(localStatus, embeddingStatus);
        String error = firstPresentError(row);
        String errorCode = deriveErrorCode(
                error, localStatus, embeddingStatus, searchability, localPresent);
        return new DerivedLifecycle(
                localStatus, embeddingStatus, searchability, error, errorCode);
    }

    private static String deriveLocalStatus(
            String localState,
            boolean localPresent,
            boolean localCurrent,
            boolean embeddingPresent) {
        if (localCurrent) {
            return "READY";
        }
        if (localPresent && "NOT_REQUESTED".equals(localState)
                && !embeddingPresent) {
            return "NOT_REQUESTED";
        }
        if (!localPresent && !embeddingPresent) {
            return "NOT_REQUESTED";
        }
        return "FAILED";
    }

    private static String deriveEmbeddingStatus(
            String embeddingState,
            boolean embeddingPresent,
            boolean embeddingCurrent,
            Map<String, Object> row) {
        if (!embeddingPresent || "NOT_REQUESTED".equals(embeddingState)) {
            return "NOT_REQUESTED";
        }
        if (embeddingCurrent) {
            return "READY";
        }
        if ("QUEUED".equals(embeddingState)
                || "PROCESSING".equals(embeddingState)
                || "RUNNING".equals(string(row.get("job_status")))) {
            return "INDEXING";
        }
        if ("FAILED".equals(embeddingState)
                || "CANCELLED".equals(embeddingState)) {
            return "FAILED";
        }
        return "NOT_REQUESTED";
    }

    private static String deriveSearchability(
            String localStatus, String embeddingStatus) {
        if ("READY".equals(localStatus)
                && "READY".equals(embeddingStatus)) {
            return "READY";
        }
        if ("READY".equals(localStatus)
                && ("INDEXING".equals(embeddingStatus)
                    || "FAILED".equals(embeddingStatus)
                    || "NOT_REQUESTED".equals(embeddingStatus))) {
            return "KEYWORD_ONLY";
        }
        if ("INDEXING".equals(embeddingStatus)) {
            return "INDEXING";
        }
        if ("NOT_REQUESTED".equals(localStatus)
                && "NOT_REQUESTED".equals(embeddingStatus)) {
            return "NOT_REQUESTED";
        }
        return "FAILED";
    }

    private static String firstPresentError(Map<String, Object> row) {
        String error = string(row.get("local_error"));
        if (error == null) {
            error = string(row.get("embedding_error"));
        }
        if (error == null) {
            error = string(row.get("job_error"));
        }
        return error;
    }

    private static String deriveErrorCode(
            String error,
            String localStatus,
            String embeddingStatus,
            String searchability,
            boolean localPresent) {
        String errorCode = null;
        if (error != null && !"READY".equals(localStatus)) {
            errorCode = "LOCAL_INDEX_FAILED";
        }
        if (error != null && "FAILED".equals(embeddingStatus)) {
            errorCode = "EMBEDDING_FAILED";
        }
        if (error == null && "FAILED".equals(searchability)
                && !localPresent) {
            errorCode = "LOCAL_INDEX_MISSING";
        }
        return errorCode;
    }

    private DocumentLifecycleResponse fromIntegrity(
            DerivationIntegrityRepository.Snapshot snapshot) {
        String searchability = switch (snapshot.bucket()) {
            case "READY" -> "READY";
            case "KEYWORD_ONLY" -> "KEYWORD_ONLY";
            case "INDEXING" -> "INDEXING";
            case "NOT_REQUESTED" -> "NOT_REQUESTED";
            default -> "FAILED";
        };
        String localStatus = snapshot.localFresh() ? "READY"
                : "NOT_REQUESTED".equals(snapshot.localCondition())
                    ? "NOT_REQUESTED" : "FAILED";
        String embeddingStatus = snapshot.vectorFresh() ? "READY"
                : "INDEXING".equals(snapshot.vectorCondition()) ? "INDEXING"
                : "NOT_REQUESTED".equals(snapshot.vectorCondition())
                    ? "NOT_REQUESTED" : "FAILED";
        String error = snapshot.localError() != null
                ? snapshot.localError() : snapshot.vectorError();
        return new DocumentLifecycleResponse(
                "ACTIVE", searchability, localStatus, embeddingStatus,
                activeProfileKey(), snapshot.activeJobId(),
                "READY".equals(searchability) ? null : snapshot.reasonCode(),
                error, !"READY".equals(searchability));
    }

    private DocumentLifecycleResponse notRequested(
            String profileKey, UUID activeJobId, String error) {
        return new DocumentLifecycleResponse(
                "ACTIVE",
                "NOT_REQUESTED",
                "NOT_REQUESTED",
                "NOT_REQUESTED",
                profileKey,
                activeJobId,
                null,
                error,
                true);
    }

    private String activeProfileKey() {
        try {
            return profileProvider.getActiveProfile().profileKey();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean positive(Object value) {
        return value instanceof Number number && number.longValue() > 0;
    }

    private static boolean equals(String expected, Object actual) {
        return expected != null && expected.equals(actual);
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
