package com.springairag.core.retrieval;

/**
 * 活动 Embedding Profile 的统一检索 SQL 作用域。
 */
public final class EmbeddingProfileSqlScope {

    private EmbeddingProfileSqlScope() {
    }

    public static String fromAndFreshness(long profileId) {
        return fromAndFreshness(
                profileId, "legacy-compatible", "json-record-v1:single");
    }

    public static String fromAndFreshness(
            long profileId,
            String textChunkerVersion,
            String jsonChunkerVersion) {
        if (profileId <= 0) {
            throw new IllegalArgumentException("Embedding profile ID must be positive");
        }
        return " FROM rag_embeddings e "
                + "JOIN rag_document_embedding_state s "
                + "ON s.document_id = e.document_id "
                + "AND s.embedding_profile_id = e.embedding_profile_id "
                + "JOIN rag_documents d ON d.id = e.document_id "
                + "WHERE e.embedding_profile_id = " + profileId + " "
                + "AND s.status = 'COMPLETED' "
                + "AND s.content_hash = d.content_hash "
                + "AND s.chunker_version = CASE "
                + "WHEN d.document_type = 'json-record' THEN "
                + sqlLiteral(jsonChunkerVersion)
                + " ELSE " + sqlLiteral(textChunkerVersion) + " END "
                + "AND d.enabled = true ";
    }

    private static String sqlLiteral(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Chunker version must not be blank");
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
