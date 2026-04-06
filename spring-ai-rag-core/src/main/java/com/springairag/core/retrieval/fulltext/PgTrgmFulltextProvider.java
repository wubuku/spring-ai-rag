package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * pg_trgm 模糊全文检索策略
 *
 * <p>使用 PostgreSQL pg_trgm 扩展的 trigram 匹配能力，
 * 通过 similarity() 函数和 % 操作符实现模糊搜索。
 *
 * <p>特点：
 * <ul>
 *   <li>字符级匹配，不依赖语言分词</li>
 *   <li>支持短词、部分匹配、轻微拼写错误</li>
 *   <li>需要 gin_trgm_ops 索引才能高效</li>
 * </ul>
 *
 * <p>作为降级策略，当 FTS 不可用时提供文本检索能力。
 */
public class PgTrgmFulltextProvider implements FulltextSearchProvider {
    
    private static final Logger log = LoggerFactory.getLogger(PgTrgmFulltextProvider.class);
    private static final double SIMILARITY_THRESHOLD = 0.1;
    
    private final JdbcTemplate jdbcTemplate;
    private final boolean available;
    
    public PgTrgmFulltextProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.available = detectAvailability();
        if (available) {
            log.info("pg_trgm full-text search provider initialized (trigram similarity)");
        }
    }
    
    private boolean detectAvailability() {
        try {
            // 检测 pg_trgm 扩展
            jdbcTemplate.queryForObject(
                    "SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class);
            // 检测 gin_trgm_ops 索引
            Boolean hasIndex = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (" +
                    "SELECT 1 FROM pg_indexes " +
                    "WHERE tablename = 'rag_embeddings' " +
                    "  AND indexdef ILIKE '%gin_trgm_ops%')",
                    Boolean.class);
            boolean available = Boolean.TRUE.equals(hasIndex);
            log.info("pg_trgm availability check: extension and index found={}", available);
            return available;
        } catch (Exception e) {
            log.warn("pg_trgm not available: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public String getName() {
        return "pg_trgm";
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
            // 设置低阈值以获得更多结果
            jdbcTemplate.update("SET pg_trgm.similarity_threshold = ?", SIMILARITY_THRESHOLD);
            
            List<Map<String, Object>> rows = executeSearch(query.trim(), documentIds, limit);
            log.debug("pg_trgm search for '{}' returned {} rows", query, rows.size());
            return rows.stream()
                    .filter(row -> !isExcluded(row, excludeIds))
                    .map(row -> {
                        double score = ((Number) row.get("score_trgm")).doubleValue();
                        return toResult(row, score);
                    })
                    .filter(r -> r.getScore() >= minScore)
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("pg_trgm search failed for query '{}': {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }
    
    private List<Map<String, Object>> executeSearch(String query, List<Long> documentIds, int limit) {
        if (documentIds != null && !documentIds.isEmpty()) {
            String placeholders = documentIds.stream()
                    .map(id -> "?").collect(Collectors.joining(","));
            String sql = String.format(
                    "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata, " +
                            "similarity(chunk_text, ?) AS score_trgm " +
                            "FROM rag_embeddings " +
                            "WHERE document_id IN (%s) " +
                            "AND chunk_text %% ? " +
                            "ORDER BY score_trgm DESC LIMIT ?",
                    placeholders);
            List<Object> args = new ArrayList<>();
            args.add(query);
            args.addAll(documentIds);
            args.add(query);
            args.add(limit);
            return jdbcTemplate.queryForList(sql, args.toArray());
        }
        
        String sql = 
                "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata, " +
                "       similarity(chunk_text, ?) AS score_trgm " +
                "FROM rag_embeddings " +
                "WHERE chunk_text % ? " +
                "ORDER BY score_trgm DESC LIMIT ?";
        return jdbcTemplate.queryForList(sql, query, query, limit);
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
