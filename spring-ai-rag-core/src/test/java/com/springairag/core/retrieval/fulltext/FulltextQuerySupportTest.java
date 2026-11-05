package com.springairag.core.retrieval.fulltext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 关键词通道打分片段测试：OR tsquery 拼装与连字符编号提取。
 */
class FulltextQuerySupportTest {

    @Test
    @DisplayName("hyphenated identifiers are extracted for exact-substring boosting")
    void identifierTerms_extractsHyphenatedCodes() {
        assertEquals(List.of("NEBULA-300"),
                FulltextQuerySupport.identifierTerms("NEBULA-300 占几个机架单元？"));
        assertEquals(List.of("RAG-4291"),
                FulltextQuerySupport.identifierTerms("RAG-4291 是什么错误？"));
        assertEquals(List.of("NEBULA-SDK-2.4.0"),
                FulltextQuerySupport.identifierTerms("NEBULA-SDK-2.4.0 新增了什么能力？"));
    }

    @Test
    @DisplayName("ordinary hyphenated words and plain words are not treated as identifiers")
    void identifierTerms_ignoresPlainWords() {
        assertTrue(FulltextQuerySupport.identifierTerms("fine-grained state-of-the-art").isEmpty());
        assertTrue(FulltextQuerySupport.identifierTerms("control plane upgrade").isEmpty());
        assertTrue(FulltextQuerySupport.identifierTerms(null).isEmpty());
        assertTrue(FulltextQuerySupport.identifierTerms("   ").isEmpty());
    }

    @Test
    @DisplayName("identifier extraction is de-duplicated and capped")
    void identifierTerms_deduplicatesAndCaps() {
        List<String> terms = FulltextQuerySupport.identifierTerms(
                "NEBULA-300 NEBULA-300 RAG-4291 X-1 Y-2 Z-3");
        assertEquals(3, terms.size());
        assertEquals("NEBULA-300", terms.get(0));
    }

    @Test
    @DisplayName("identifier boost SQL binds one parameter per identifier")
    void identifierBoostSql_bindsOneParamPerTerm() {
        assertEquals("", FulltextQuerySupport.identifierBoostSql(List.of()));
        String sql = FulltextQuerySupport.identifierBoostSql(
                List.of("NEBULA-300", "RAG-4291"));
        assertTrue(sql.contains("strpos(lower(e.chunk_text), lower(?))"));
        assertEquals(2, sql.chars().filter(ch -> ch == '?').count());
    }

    @Test
    @DisplayName("OR tsquery subquery tokenizes in the database and joins lexemes with |")
    void tsQueryLexemeSubquery_usesOrJoin() {
        String sql = FulltextQuerySupport.tsQueryLexemeSubquery("english");
        assertTrue(sql.contains("to_tsquery('english'"));
        assertTrue(sql.contains("tsvector_to_array(to_tsvector('english', ?))"));
        assertTrue(sql.contains("string_agg(quote_literal(lex), ' | ')"));
        assertTrue(sql.contains("btrim(lex) <> ''"));
    }

    @Test
    @DisplayName("coverage floor requires half the lexemes (rounded up) for long queries")
    void coverageFloorSql_requiresTwoLexemesForLongQueries() {
        String sql = FulltextQuerySupport.coverageFloorSql(
                "jiebacfg", "to_tsvector('jiebacfg', e.chunk_text)",
                "e.chunk_text", List.of());
        assertTrue(sql.startsWith("AND "));
        assertTrue(sql.contains("doc_lex IN (SELECT lex FROM unnest(tsvector_to_array"));
        assertTrue(sql.contains("count(*) >= 3 THEN (count(*) + 1) / 2 ELSE 1 END"));
        assertEquals(2, sql.chars().filter(ch -> ch == '?').count());
    }

    @Test
    @DisplayName("exact identifier hits bypass the coverage floor")
    void coverageFloorSql_letsIdentifiersThrough() {
        String sql = FulltextQuerySupport.coverageFloorSql(
                "jiebacfg", "to_tsvector('jiebacfg', e.chunk_text)",
                "e.chunk_text", List.of("NEBULA-300", "RAG-4291"));
        assertTrue(sql.contains("OR strpos(lower(e.chunk_text), lower(?)) > 0"));
        assertEquals(4, sql.chars().filter(ch -> ch == '?').count());
    }
}
