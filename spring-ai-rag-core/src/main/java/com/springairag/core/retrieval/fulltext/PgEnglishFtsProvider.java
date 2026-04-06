package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * PostgreSQL 内置英文全文检索策略
 *
 * <p>使用 PostgreSQL 内置的 'english' 文本搜索配置，
 * 通过预建的 search_vector_en GENERATED 列和 GIN 索引实现高效英文全文检索。
 *
 * <p>特点：
 * <ul>
 *   <li>使用内置 english 配置，无需额外扩展</li>
 *   <li>依赖 search_vector_en GENERATED 列和 GIN 索引</li>
 *   <li>使用 websearch_to_tsquery 支持 Google 风格搜索语法</li>
 * </ul>
 */
public class PgEnglishFtsProvider implements FulltextSearchProvider {
    
    private static final Logger log = LoggerFactory.getLogger(PgEnglishFtsProvider.class);
    private static final String TS_CONFIG = "english";
    
    private final JdbcTemplate jdbcTemplate;
    private final boolean available;
    
    public PgEnglishFtsProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.available = detectAvailability();
        if (available) {
            log.info("English FTS full-text search provider initialized (english config)");
        }
    }
    
    private boolean detectAvailability() {
        try {
            // 检测 english tsvector GIN 索引
            Boolean hasIndex = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (" +
                    "SELECT 1 FROM pg_indexes " +
                    "WHERE schemaname = 'public' " +
                    "  AND tablename = 'rag_embeddings' " +
                    "  AND indexdef ILIKE '%search_vector_en%gin%')",
                    Boolean.class);
            boolean available = Boolean.TRUE.equals(hasIndex);
            log.info("English FTS availability check: index found={}", available);
            return available;
        } catch (Exception e) {
            log.warn("English FTS not available: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public String getName() {
        return "english_fts";
    }
    
    @Override
    public boolean isAvailable() {
        return available;
    }
    
    @Override
    public List<RetrievalResult> search(String query, List<Long> documentIds,
                                        List<Long> excludeIds, int limit, double minScore) {
        if (!available) return Collections.emptyList();
        if (query == null || query.isBlank()) return Collections.emptyList();
        
        try {
            List<Map<String, Object>> rows = executeSearch(query.trim(), documentIds, limit);
            log.debug("English FTS search for '{}' returned {} rows", query, rows.size());
            return rows.stream()
                    .filter(row -> !isExcluded(row, excludeIds))
                    .map(row -> {
                        double rank = ((Number) row.get("rank")).doubleValue();
                        return toResult(row, rank);
                    })
                    .filter(r -> r.getScore() >= minScore)
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("English FTS search failed for query '{}': {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }
    
    private List<Map<String, Object>> executeSearch(String query, List<Long> documentIds, int limit) {
        if (documentIds != null && !documentIds.isEmpty()) {
            String placeholders = documentIds.stream()
                    .map(id -> "?").collect(Collectors.joining(","));
            String sql = String.format(
                    "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata, " +
                            "ts_rank_cd(search_vector_en, q) as rank " +
                            "FROM rag_embeddings, " +
                            "     websearch_to_tsquery('%s', ?) AS q " +
                            "WHERE document_id IN (%s) " +
                            "AND search_vector_en @@ q " +
                            "ORDER BY rank DESC LIMIT ?",
                    TS_CONFIG, placeholders);
            List<Object> args = new ArrayList<>();
            args.add(query);
            args.addAll(documentIds);
            args.add(limit);
            return jdbcTemplate.queryForList(sql, args.toArray());
        }
        
        String sql = 
                "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata, " +
                "       ts_rank_cd(search_vector_en, q) as rank " +
                "FROM rag_embeddings, " +
                "     websearch_to_tsquery('" + TS_CONFIG + "', ?) AS q " +
                "WHERE search_vector_en @@ q " +
                "ORDER BY rank DESC LIMIT ?";
        return jdbcTemplate.queryForList(sql, query, limit);
    }
    
    private boolean isExcluded(Map<String, Object> row, List<Long> excludeIds) {
        if (excludeIds == null || excludeIds.isEmpty()) return false;
        return excludeIds.contains(((Number) row.get("id")).longValue());
    }
    
    private RetrievalResult toResult(Map<String, Object> row, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(String.valueOf(row.get("document_id")));
        r.setChunkText((String) row.get("chunk_text"));
        r.setScore(score);
        r.setVectorScore(0.0);
        r.setFulltextScore(score);
        r.setChunkIndex(((Number) row.get("chunk_index")).intValue());
        Object metadata = row.get("metadata");
        if (metadata instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) metadata;
            r.setMetadata(meta);
        }
        return r;
    }
}
