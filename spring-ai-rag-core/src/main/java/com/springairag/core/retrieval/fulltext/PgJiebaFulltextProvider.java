package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * pg_jieba Chinese tokenization full-text search strategy.
 *
 * <p>Uses PostgreSQL pg_jieba extension with `jiebacfg` text search configuration,
 * via a pre-built search_vector_zh GENERATED column (with GIN index) for efficient Chinese FTS.
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>pg_jieba extension is installed in the database</li>
 *   <li>V15 migration created the search_vector_zh GENERATED column and jiebacfg text search configuration</li>
 *   <li>search_vector_zh column has a GIN index</li>
 * </ul>
 */
public class PgJiebaFulltextProvider implements FulltextSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(PgJiebaFulltextProvider.class);
    private static final String TS_CONFIG = "jiebacfg";

    private final JdbcTemplate jdbcTemplate;
    private final boolean available;

    public PgJiebaFulltextProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.available = detectAvailability();
        if (available) {
            log.info("pg_jieba full-text search provider initialized (Chinese segmentation, indexed)");
        }
    }

    private boolean detectAvailability() {
        try {
            // Detect pg_jieba extension
            jdbcTemplate.queryForObject(
                    "SELECT 1 FROM pg_extension WHERE extname = 'pg_jieba'", Integer.class);
            // Detect jiebacfg configuration
            jdbcTemplate.queryForObject(
                    "SELECT 1 FROM pg_ts_config WHERE cfgname = 'jiebacfg'", Integer.class);
            // Detect search_vector_zh GIN index
            Boolean hasIndex = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (" +
                    "SELECT 1 FROM pg_indexes " +
                    "WHERE schemaname = 'public' " +
                    "  AND tablename = 'rag_embeddings' " +
                    "  AND indexdef ILIKE '%search_vector_zh%gin%')",
                    Boolean.class);
            boolean indexAvailable = Boolean.TRUE.equals(hasIndex);
            log.info("pg_jieba availability check: extension={}, config={}, index={}",
                    true, true, indexAvailable);
            return indexAvailable;
        } catch (Exception e) { // Health probe: must never throw, graceful degradation
            log.warn("pg_jieba not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getName() {
        return "pg_jieba";
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
            log.debug("pg_jieba search for '{}' returned {} rows", query, rows.size());
            return rows.stream()
                    .filter(row -> !isExcluded(row, excludeIds))
                    .map(row -> {
                        double rank = ((Number) row.get("rank")).doubleValue();
                        return toResult(row, rank);
                    })
                    .filter(r -> r.getScore() >= minScore)
                    .limit(limit)
                    .toList();
        } catch (Exception e) { // Resilience: return empty on search failure
            log.warn("pg_jieba search failed for query '{}': {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> executeSearch(String query, List<Long> documentIds, int limit) {
        // Use pre-built search_vector_zh column (with GIN index)
        // Use websearch_to_tsquery for Google-style search syntax
        if (documentIds != null && !documentIds.isEmpty()) {
            String placeholders = documentIds.stream()
                    .map(id -> "?").collect(Collectors.joining(","));
            String sql = String.format(
                    "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata, " +
                            "ts_rank_cd(search_vector_zh, q) as rank " +
                            "FROM rag_embeddings, " +
                            "     websearch_to_tsquery('%s', ?) AS q " +
                            "WHERE document_id IN (%s) " +
                            "AND search_vector_zh IS NOT NULL " +
                            "AND search_vector_zh @@ q " +
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
                "       ts_rank_cd(search_vector_zh, q) as rank " +
                "FROM rag_embeddings, " +
                "     websearch_to_tsquery('" + TS_CONFIG + "', ?) AS q " +
                "WHERE search_vector_zh IS NOT NULL " +
                "AND search_vector_zh @@ q " +
                "ORDER BY rank DESC LIMIT ?";
        return jdbcTemplate.queryForList(sql, query, limit);
    }

    private boolean isExcluded(Map<String, Object> row, List<Long> excludeIds) {
        if (excludeIds == null || excludeIds.isEmpty()) return false;
        return excludeIds.contains(((Number) row.get("id")).longValue());
    }

    private RetrievalResult toResult(Map<String, Object> row, double rank) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(String.valueOf(row.get("document_id")));
        r.setChunkText((String) row.get("chunk_text"));
        r.setScore(rank);
        r.setVectorScore(0.0);
        r.setFulltextScore(rank);
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
