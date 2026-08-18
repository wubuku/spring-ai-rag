package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.EmbeddingProfileSqlScope;
import com.springairag.core.retrieval.JsonbContainmentFilter;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalResultProvenance;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.retrieval.RetrievalScopeSql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

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
                                        List<Long> excludeIds, int limit, double minScore,
                                        long embeddingProfileId) {
        return searchInScope(query, RetrievalScope.forDocumentIds(documentIds),
                excludeIds, limit, minScore, embeddingProfileId);
    }

    @Override
    public List<RetrievalResult> searchInScope(
            String query, RetrievalScope scope, List<Long> excludeIds,
            int limit, double minScore, long embeddingProfileId) {
        return searchInScope(
                query, scope, excludeIds, limit, minScore,
                embeddingProfileId, RetrievalFilters.none());
    }

    @Override
    public List<RetrievalResult> searchInScope(
            String query, RetrievalScope scope, List<Long> excludeIds,
            int limit, double minScore, long embeddingProfileId,
            JsonbContainmentFilter payloadFilter) {
        return searchInScope(
                query, scope, excludeIds, limit, minScore,
                embeddingProfileId, RetrievalFilters.ofPayload(payloadFilter));
    }

    @Override
    public List<RetrievalResult> searchInScope(
            String query, RetrievalScope scope, List<Long> excludeIds,
            int limit, double minScore, long embeddingProfileId,
            RetrievalFilters filters) {
        return searchInScopeDetailed(
                query, scope, excludeIds, limit, minScore,
                embeddingProfileId, filters).results();
    }

    @Override
    public SearchResult searchInScopeDetailed(
            String query, RetrievalScope scope, List<Long> excludeIds,
            int limit, double minScore, long embeddingProfileId,
            RetrievalFilters filters) {
        if (!available) return SearchResult.success(List.of());
        if (query == null || query.isBlank()) return SearchResult.success(List.of());
        if (scope != null && scope.matchNone()) return SearchResult.success(List.of());
        try {
            List<Map<String, Object>> rows =
                    executeSearch(
                            query.trim(), scope, limit,
                            embeddingProfileId, filters);
            log.debug("pg_jieba search for '{}' returned {} rows", query, rows.size());
            return SearchResult.success(rows.stream()
                    .filter(row -> !isExcluded(row, excludeIds))
                    .map(row -> {
                        // ts_rank can return NULL for edge cases
                        Number rankNum = (Number) row.get("rank");
                        double rank = rankNum != null ? rankNum.doubleValue() : 0.0;
                        return toResult(row, rank);
                    })
                    .limit(limit)
                    .toList());
        } catch (Exception e) { // Resilience: return empty on search failure
            log.warn("pg_jieba search failed for query '{}': {}", query, e.getMessage());
            return SearchResult.failure(e.getClass().getSimpleName());
        }
    }

    private List<Map<String, Object>> executeSearch(
            String query, RetrievalScope retrievalScope,
            int limit, long embeddingProfileId,
            RetrievalFilters filters) {
        // Use pre-built search_vector_zh column (with GIN index)
        // Use websearch_to_tsquery for Google-style search syntax
        String select = "SELECT e.id, e.chunk_text, e.document_id, e.chunk_index, e.metadata, "
                + "d.title AS document_title, d.source AS document_source, "
                + "d.original_filename AS original_filename, "
                + "ts_rank_cd(e.search_vector_zh, "
                + "websearch_to_tsquery('" + TS_CONFIG + "', ?)) AS rank";
        String scope = EmbeddingProfileSqlScope.fromAndFreshness(embeddingProfileId);
        RetrievalScopeSql.Fragment fragment =
                RetrievalScopeSql.build(retrievalScope, filters);
        String sql = select + scope + fragment.sql()
                + "AND e.search_vector_zh IS NOT NULL "
                + "AND e.search_vector_zh @@ websearch_to_tsquery('"
                + TS_CONFIG + "', ?) "
                + "ORDER BY rank DESC LIMIT ?";
        List<Object> args = new ArrayList<>();
        args.add(query);
        args.addAll(fragment.args());
        args.add(query);
        args.add(limit);
        return jdbcTemplate.queryForList(sql, args.toArray());
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
        RetrievalResultProvenance.applyDocumentFields(r, row);
        return r;
    }
}
