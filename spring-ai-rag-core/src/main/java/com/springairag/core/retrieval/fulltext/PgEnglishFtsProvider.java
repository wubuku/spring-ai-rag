package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.EmbeddingProfileSqlScope;
import com.springairag.core.retrieval.JsonbContainmentFilter;
import com.springairag.core.retrieval.RetrievalResultProvenance;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.retrieval.RetrievalScopeSql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * PostgreSQL built-in English full-text search strategy
 *
 * <p>Uses PostgreSQL built-in 'english' text search configuration,
 * providing efficient English full-text search via pre-built search_vector_en GENERATED column and GIN index.
 *
 * <p>Features:
 * <ul>
 *   <li>Uses built-in english config, no extra extension needed</li>
 *   <li>Depends on search_vector_en GENERATED column and GIN index</li>
 *   <li>Uses websearch_to_tsquery supporting Google-style search syntax</li>
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
            // Detect english tsvector GIN index
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
            // Resilience: DB query failure means FTS not available (graceful degradation)
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
                embeddingProfileId, null);
    }

    @Override
    public List<RetrievalResult> searchInScope(
            String query, RetrievalScope scope, List<Long> excludeIds,
            int limit, double minScore, long embeddingProfileId,
            JsonbContainmentFilter payloadFilter) {
        if (!available) return Collections.emptyList();
        if (query == null || query.isBlank()) return Collections.emptyList();
        if (scope != null && scope.matchNone()) return Collections.emptyList();
        
        try {
            List<Map<String, Object>> rows =
                    executeSearch(
                            query.trim(), scope, limit,
                            embeddingProfileId, payloadFilter);
            log.debug("English FTS search for '{}' returned {} rows", query, rows.size());
            return rows.stream()
                    .filter(row -> !isExcluded(row, excludeIds))
                    .map(row -> {
                        // ts_rank can return NULL for edge cases
                        Number rankNum = (Number) row.get("rank");
                        double rank = rankNum != null ? rankNum.doubleValue() : 0.0;
                        return toResult(row, rank);
                    })
                    .limit(limit)
                    .toList();
        } catch (Exception e) {
            // Resilience: search failure returns empty list (graceful degradation)
            log.warn("English FTS search failed for query '{}': {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }
    
    private List<Map<String, Object>> executeSearch(
            String query, RetrievalScope retrievalScope,
            int limit, long embeddingProfileId,
            JsonbContainmentFilter payloadFilter) {
        String select = "SELECT e.id, e.chunk_text, e.document_id, e.chunk_index, e.metadata, "
                + "d.title AS document_title, d.source AS document_source, "
                + "d.original_filename AS original_filename, "
                + "ts_rank_cd(e.search_vector_en, "
                + "websearch_to_tsquery('" + TS_CONFIG + "', ?)) AS rank";
        String scope = EmbeddingProfileSqlScope.fromAndFreshness(embeddingProfileId);
        RetrievalScopeSql.Fragment fragment =
                RetrievalScopeSql.build(retrievalScope, payloadFilter);
        String sql = select + scope + fragment.sql()
                + "AND e.search_vector_en @@ websearch_to_tsquery('"
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
