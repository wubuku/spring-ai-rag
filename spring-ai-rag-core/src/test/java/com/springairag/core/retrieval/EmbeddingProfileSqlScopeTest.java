package com.springairag.core.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖活动 Embedding Profile 的检索 SQL 作用域生成：
 * profile 参数校验、chunker 版本字面量转义、JOIN 与 freshness 条件、
 * 两参重载的 legacy 默认版本。
 */
class EmbeddingProfileSqlScopeTest {

    @Test
    void rejectsNonPositiveProfileId() {
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingProfileSqlScope.fromAndFreshness(0));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingProfileSqlScope.fromAndFreshness(-7L, "v1", "jv1"));
    }

    @Test
    void rejectsBlankChunkerVersions() {
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingProfileSqlScope.fromAndFreshness(7L, " ", "jv1"));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingProfileSqlScope.fromAndFreshness(7L, "v1", null));
    }

    @Test
    void buildsJoinAndFreshnessConditionsWithProfileBinding() {
        String sql = EmbeddingProfileSqlScope.fromAndFreshness(7L, "v1", "jv1");

        assertTrue(sql.contains("FROM rag_embeddings e"));
        assertTrue(sql.contains("JOIN rag_document_embedding_state s"));
        assertTrue(sql.contains("JOIN rag_documents d"));
        assertTrue(sql.contains("e.embedding_profile_id = 7"));
        assertTrue(sql.contains("s.status = 'COMPLETED'"));
        assertTrue(sql.contains("s.content_hash = d.content_hash"));
        assertTrue(sql.contains("WHEN d.document_type = 'json-record' THEN 'jv1'"));
        assertTrue(sql.contains("ELSE 'v1'"));
        assertTrue(sql.contains("d.enabled = true"));
    }

    @Test
    void escapesSingleQuotesInChunkerVersions() {
        String sql = EmbeddingProfileSqlScope.fromAndFreshness(
                7L, "it's-v1", "jv1");

        assertTrue(sql.contains("'it''s-v1'"));
    }

    @Test
    void twoArgOverloadDefaultsToLegacyCompatibleTextChunker() {
        String sql = EmbeddingProfileSqlScope.fromAndFreshness(7L);

        assertTrue(sql.contains("ELSE 'legacy-compatible'"));
    }
}
