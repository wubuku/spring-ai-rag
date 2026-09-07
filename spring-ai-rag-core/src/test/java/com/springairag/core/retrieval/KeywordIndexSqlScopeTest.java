package com.springairag.core.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖本地关键词 chunk 的统一 SQL freshness 作用域生成：
 * profile 参数校验、chunker 版本字面量转义、JOIN 与 freshness 条件。
 */
class KeywordIndexSqlScopeTest {

    @Test
    void rejectsNonPositiveProfileId() {
        assertThrows(IllegalArgumentException.class,
                () -> KeywordIndexSqlScope.fromAndFreshness(0, "v1", "jv1"));
        assertThrows(IllegalArgumentException.class,
                () -> KeywordIndexSqlScope.fromAndFreshness(-1, "v1", "jv1"));
    }

    @Test
    void rejectsBlankChunkerVersions() {
        assertThrows(IllegalArgumentException.class,
                () -> KeywordIndexSqlScope.fromAndFreshness(7L, " ", "jv1"));
        assertThrows(IllegalArgumentException.class,
                () -> KeywordIndexSqlScope.fromAndFreshness(7L, "v1", ""));
    }

    @Test
    void buildsJoinAndFreshnessConditions() {
        String sql = KeywordIndexSqlScope.fromAndFreshness(7L, "v1", "jv1");

        assertTrue(sql.contains("FROM rag_document_chunks e"));
        assertTrue(sql.contains("JOIN rag_document_local_index_state s"));
        assertTrue(sql.contains("JOIN rag_documents d"));
        assertTrue(sql.contains("LEFT JOIN rag_document_embedding_state vs"));
        assertTrue(sql.contains("LEFT JOIN rag_embeddings v"));
        assertTrue(sql.contains("vs.embedding_profile_id = 7"));
        assertTrue(sql.contains("v.embedding_profile_id = 7"));
        assertTrue(sql.contains("s.local_index_status = 'READY'"));
        assertTrue(sql.contains("d.enabled = true"));
        assertTrue(sql.contains("WHEN d.document_type = 'json-record' THEN 'jv1'"));
        assertTrue(sql.contains("ELSE 'v1'"));
    }

    @Test
    void escapesSingleQuotesInChunkerVersions() {
        String sql = KeywordIndexSqlScope.fromAndFreshness(7L, "it's-v1", "jv1");

        // 单引号被转义为两个单引号，不会破坏 SQL 字面量。
        assertTrue(sql.contains("'it''s-v1'"));
    }
}
