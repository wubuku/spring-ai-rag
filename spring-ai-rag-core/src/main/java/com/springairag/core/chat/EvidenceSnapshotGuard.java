package com.springairag.core.chat;

import com.springairag.api.dto.ChatSource;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EvidenceSnapshotGuard {

    private final JdbcTemplate jdbc;

    public EvidenceSnapshotGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void validate(List<ChatSource> sources) {
        if (sources == null) {
            return;
        }
        for (ChatSource source : sources) {
            if (source == null || source.getEvidenceSnapshot() == null) {
                continue;
            }
            var snapshot = source.getEvidenceSnapshot();
            String state;
            Object[] parameters;
            if ("VECTOR".equals(snapshot.indexKind())) {
                state = "JOIN rag_document_embedding_state s ON s.document_id = d.id "
                        + "AND s.embedding_profile_id = ? AND s.request_generation = ? "
                        + "AND s.status = 'COMPLETED' ";
                parameters = new Object[]{snapshot.embeddingProfileId(), snapshot.generation(),
                        source.getDocumentId(), snapshot.documentRevision(), snapshot.contentHash(),
                        snapshot.collectionId(), snapshot.chunkerVersion()};
            } else if ("KEYWORD".equals(snapshot.indexKind())) {
                state = "JOIN rag_document_local_index_state s ON s.document_id = d.id "
                        + "AND s.local_index_generation = ? AND s.local_index_status = 'READY' ";
                parameters = new Object[]{snapshot.generation(), source.getDocumentId(),
                        snapshot.documentRevision(), snapshot.contentHash(), snapshot.collectionId(),
                        snapshot.chunkerVersion()};
            } else {
                throw conflict();
            }
            Boolean valid = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM rag_documents d "
                    + state + "LEFT JOIN rag_collection c ON c.id = d.collection_id "
                    + "WHERE d.id = CAST(? AS bigint) AND d.document_revision = ? "
                    + "AND d.content_hash = ? AND d.collection_id IS NOT DISTINCT FROM CAST(? AS bigint) "
                    + "AND s.chunker_version = ? AND s.content_hash = d.content_hash "
                    + "AND d.enabled = TRUE AND d.source_deleted_at IS NULL "
                    + "AND (d.collection_id IS NULL OR (c.deleted = FALSE AND c.enabled = TRUE "
                    + "AND c.purged_at IS NULL)))", Boolean.class, parameters);
            if (!Boolean.TRUE.equals(valid)) {
                throw conflict();
            }
        }
    }

    public static RagException conflict() {
        return new RagException(ErrorCode.EVIDENCE_SNAPSHOT_CONFLICT,
                "Evidence changed during answer generation; submit a new request");
    }
}
