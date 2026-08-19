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
 * 将文档、Profile 状态和持久化任务归一为稳定的公开生命周期读模型。
 */
@Service
public class DocumentLifecycleService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProfileProvider profileProvider;
    private final DocumentDerivationDescriptorProvider descriptorProvider;

    public DocumentLifecycleService(
            JdbcTemplate jdbcTemplate,
            EmbeddingProfileProvider profileProvider,
            DocumentDerivationDescriptorProvider descriptorProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.profileProvider = profileProvider;
        this.descriptorProvider = descriptorProvider;
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

        EmbeddingProfile profile = profileProvider.getActiveProfile();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT state.status,
                       state.content_hash,
                       state.chunker_version,
                       state.chunk_count,
                       state.processing_error,
                       state.active_job_id,
                       job.status AS job_status,
                       job.last_error AS job_error
                FROM rag_document_embedding_state state
                LEFT JOIN rag_embedding_jobs job
                  ON job.id = state.active_job_id
                WHERE state.document_id = ?
                  AND state.embedding_profile_id = ?
                """,
                document.getId(),
                profile.id());
        if (rows.isEmpty()) {
            return lifecycle(
                    "NOT_REQUESTED", profile.profileKey(), null,
                    null, "No derived index has been requested");
        }

        Map<String, Object> row = rows.getFirst();
        String state = String.valueOf(row.get("status"));
        boolean currentHash = document.getContentHash() != null
                && document.getContentHash().equals(row.get("content_hash"));
        boolean currentChunker = descriptorProvider.describe(document)
                .chunkerVersion().equals(row.get("chunker_version"));
        int chunkCount = row.get("chunk_count") instanceof Number number
                ? number.intValue() : 0;
        UUID activeJobId = row.get("active_job_id") instanceof UUID uuid
                ? uuid : null;
        String error = string(row.get("processing_error"));
        if (error == null) {
            error = string(row.get("job_error"));
        }

        if ("COMPLETED".equals(state) && currentHash
                && currentChunker && chunkCount > 0) {
            return new DocumentLifecycleResponse(
                    "ACTIVE", "READY", "READY", "READY",
                    profile.profileKey(), activeJobId,
                    null, error, false);
        }
        if ("QUEUED".equals(state) || "PROCESSING".equals(state)) {
            return new DocumentLifecycleResponse(
                    "ACTIVE", "INDEXING", "INDEXING", "INDEXING",
                    profile.profileKey(), activeJobId,
                    null, error, true);
        }
        if ("FAILED".equals(state) || "CANCELLED".equals(state)) {
            return new DocumentLifecycleResponse(
                    "ACTIVE", "FAILED", "FAILED", "FAILED",
                    profile.profileKey(), activeJobId,
                    "CANCELLED".equals(state)
                            ? "INDEXING_CANCELLED" : "EMBEDDING_FAILED",
                    error, true);
        }
        return lifecycle(
                "NOT_REQUESTED", profile.profileKey(), activeJobId,
                null, error);
    }

    private DocumentLifecycleResponse lifecycle(
            String searchability,
            String profileKey,
            UUID activeJobId,
            String errorCode,
            String error) {
        return new DocumentLifecycleResponse(
                "ACTIVE",
                searchability,
                searchability,
                searchability,
                profileKey,
                activeJobId,
                errorCode,
                error,
                !"READY".equals(searchability)
                        && !"DISABLED".equals(searchability));
    }

    private String activeProfileKey() {
        try {
            return profileProvider.getActiveProfile().profileKey();
        } catch (RuntimeException ignored) {
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
