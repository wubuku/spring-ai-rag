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
 * pg_trgm fuzzy full-text search strategy
 *
 * <p>Uses PostgreSQL pg_trgm extension trigram matching capability,
 * providing fuzzy search via similarity() function and % operator.
 *
 * <p>Features:
 * <ul>
 *   <li>Character-level matching, language-independent</li>
 *   <li>Supports short words, partial match, minor spelling errors</li>
 *   <li>Requires gin_trgm_ops index for efficiency</li>
 * </ul>
 *
 * <p>As fallback strategy, provides text search when FTS is unavailable.
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
            // Detect pg_trgm extension
            jdbcTemplate.queryForObject(
                    "SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class);
            // Detect gin_trgm_ops index
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
            // Resilience: DB query failure means pg_trgm not available (graceful degradation)
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
            // Set low threshold to get more results
            jdbcTemplate.update("SET pg_trgm.similarity_threshold = ?", SIMILARITY_THRESHOLD);
            
            List<Map<String, Object>> rows =
                    executeSearch(
                            query.trim(), scope, limit,
                            embeddingProfileId, filters);
            log.debug("pg_trgm search for '{}' returned {} rows", query, rows.size());
            List<RetrievalResult> candidates = rows.stream()
                    .filter(row -> !isExcluded(row, excludeIds))
                    .map(row -> {
                        // similarity() can return NULL for edge cases (e.g., empty strings)
                        Number scoreNum = (Number) row.get("score_trgm");
                        double score = scoreNum != null ? scoreNum.doubleValue() : 0.0;
                        return toResult(row, score);
                    })
                    .toList();
            List<RetrievalResult> results = candidates.stream()
                    .filter(r -> r.getScore() >= minScore)
                    .limit(limit)
                    .toList();
            return SearchResult.success(results, candidates.size());
        } catch (Exception e) {
            // Resilience: search failure returns empty list (graceful degradation)
            log.warn("pg_trgm search failed for query '{}': {}", query, e.getMessage());
            return SearchResult.failure(e.getClass().getSimpleName());
        }
    }
    
    List<Map<String, Object>> executeSearch(
            String query, RetrievalScope retrievalScope,
            int limit, long embeddingProfileId,
            RetrievalFilters filters) {
        if (filters == null || filters.isEmpty()) {
            return executeSearch(
                    query, retrievalScope, limit, embeddingProfileId);
        }
        return executeSearchInternal(
                query, retrievalScope, limit,
                embeddingProfileId, filters);
    }

    List<Map<String, Object>> executeSearch(
            String query, RetrievalScope retrievalScope,
            int limit, long embeddingProfileId) {
        return executeSearchInternal(
                query, retrievalScope, limit,
                embeddingProfileId, RetrievalFilters.none());
    }

    private List<Map<String, Object>> executeSearchInternal(
            String query, RetrievalScope retrievalScope,
            int limit, long embeddingProfileId,
            RetrievalFilters filters) {
        String select = "SELECT e.id, e.chunk_text, e.document_id, e.chunk_index, e.metadata, "
                + "d.title AS document_title, d.source AS document_source, "
                + "d.original_filename AS original_filename, "
                + "similarity(e.chunk_text, ?) AS score_trgm";
        String scope = EmbeddingProfileSqlScope.fromAndFreshness(embeddingProfileId);
        RetrievalScopeSql.Fragment fragment =
                RetrievalScopeSql.build(retrievalScope, filters);
        String sql = select + scope + fragment.sql()
                + "AND e.chunk_text % ? "
                + "ORDER BY score_trgm DESC LIMIT ?";
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
        RetrievalResultProvenance.applyDocumentFields(r, row);
        return r;
    }
}
