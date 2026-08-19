package com.springairag.core.retrieval;

/**
 * 本地关键词 chunk 的统一 SQL freshness 作用域。
 *
 * <p>全文检索不再依赖 embedding profile 是否有向量；profile 参数只用于
 * 可选地映射既有 {@code excludeIds} 契约中的 rag_embeddings.id。</p>
 */
public final class KeywordIndexSqlScope {

    private KeywordIndexSqlScope() {
    }

    public static String fromAndFreshness(
            long embeddingProfileId,
            String textChunkerVersion,
            String jsonChunkerVersion) {
        if (embeddingProfileId <= 0) {
            throw new IllegalArgumentException(
                    "Embedding profile ID must be positive");
        }
        return " FROM rag_document_chunks e "
                + "JOIN rag_document_local_index_state s "
                + "ON s.document_id = e.document_id "
                + "AND s.local_index_generation = e.local_index_generation "
                + "JOIN rag_documents d ON d.id = e.document_id "
                + "LEFT JOIN rag_document_embedding_state vs "
                + "ON vs.document_id = e.document_id "
                + "AND vs.embedding_profile_id = " + embeddingProfileId + " "
                + "AND vs.status = 'COMPLETED' "
                + "AND vs.content_hash = d.content_hash "
                + "AND vs.chunker_version = CASE "
                + "WHEN d.document_type = 'json-record' THEN "
                + sqlLiteral(jsonChunkerVersion)
                + " ELSE " + sqlLiteral(textChunkerVersion) + " END "
                + "LEFT JOIN rag_embeddings v "
                + "ON v.document_id = e.document_id "
                + "AND v.embedding_profile_id = " + embeddingProfileId + " "
                + "AND v.chunk_index = e.chunk_index "
                + "AND vs.document_id IS NOT NULL "
                + "WHERE s.local_index_status = 'READY' "
                + "AND s.content_hash = d.content_hash "
                + "AND s.chunker_version = CASE "
                + "WHEN d.document_type = 'json-record' THEN "
                + sqlLiteral(jsonChunkerVersion)
                + " ELSE " + sqlLiteral(textChunkerVersion) + " END "
                + "AND e.content_hash = d.content_hash "
                + "AND e.chunker_version = CASE "
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
