package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.JsonbContainmentFilter;
import com.springairag.core.retrieval.KeywordIndexSqlScope;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalResultProvenance;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.retrieval.RetrievalScopeSql;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.config.RagProperties;
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
 * <p>查询侧先由 jiebacfg 分词，再以 OR 词项 + {@code ts_rank_cd} 排序；严格 AND 会把
 * 中文长查询的候选压到 0–2 条，等于让关键词通道失效。标题单独加权，连字符编号
 * （如 {@code NEBULA-300}）按原文子串精确命中额外加权，避免被近邻编号挤掉。</p>
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
    private final DocumentDerivationDescriptorProvider descriptorProvider;

    public PgJiebaFulltextProvider(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RagProperties());
    }

    public PgJiebaFulltextProvider(
            JdbcTemplate jdbcTemplate,
            RagProperties ragProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.descriptorProvider =
                new DocumentDerivationDescriptorProvider(ragProperties);
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
                    "  AND tablename = 'rag_document_chunks' " +
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
            log.debug("pg_jieba search returned {} rows (queryChars={})", rows.size(), query.length());
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
            log.warn("pg_jieba search failed (queryChars={}): {}", query.length(), e.getMessage());
            return SearchResult.failure(e.getClass().getSimpleName());
        }
    }

    private List<Map<String, Object>> executeSearch(
            String query, RetrievalScope retrievalScope,
            int limit, long embeddingProfileId,
            RetrievalFilters filters) {
        // 查询文本由 jiebacfg 分词后拼成 OR tsquery，再按 ts_rank_cd 排序。
        // 旧的 websearch_to_tsquery 是 AND 语义，中文长查询会直接把候选压到 0–2 条。
        String tsQuery = FulltextQuerySupport.tsQueryLexemeSubquery(TS_CONFIG);
        List<String> identifiers = FulltextQuerySupport.identifierTerms(query);
        String chunkVector = "to_tsvector('" + TS_CONFIG + "', e.chunk_text)";
        String select = "SELECT e.id AS local_chunk_id, v.id AS embedding_id, "
                + "d.document_revision AS evidence_revision, d.content_hash AS evidence_content_hash, "
                + "'KEYWORD' AS evidence_index_kind, NULL::bigint AS evidence_profile_id, "
                + "s.local_index_generation AS evidence_generation, s.chunker_version AS evidence_chunker, "
                + "e.chunk_text, e.document_id, e.chunk_index, "
                + currentMetadataSql() + ", "
                + "d.collection_id AS collection_id, "
                + "(SELECT c.collection_key FROM rag_collection c WHERE c.id = d.collection_id) AS collection_key, "
                + "d.title AS document_title, d.source AS document_source, "
                + "d.original_filename AS original_filename, "
                + "("
                + "ts_rank_cd(" + chunkVector + ", (" + tsQuery + "))"
                + " + " + FulltextQuerySupport.titleWeight()
                + " * ts_rank_cd(to_tsvector('" + TS_CONFIG + "', COALESCE(d.title, '')), ("
                + tsQuery + "))"
                + FulltextQuerySupport.identifierBoostSql(identifiers)
                + ") AS rank";
        String scope = KeywordIndexSqlScope.fromAndFreshness(
                embeddingProfileId,
                descriptorProvider.textDescriptor().chunkerVersion(),
                descriptorProvider.jsonRecordDescriptor().chunkerVersion());
        RetrievalScopeSql.Fragment fragment =
                RetrievalScopeSql.build(retrievalScope, filters);
        String sql = select + scope + fragment.sql()
                + "AND " + chunkVector + " @@ (" + tsQuery + ") "
                + FulltextQuerySupport.coverageFloorSql(
                        TS_CONFIG, chunkVector, "e.chunk_text", identifiers)
                + "ORDER BY rank DESC, e.document_id, e.chunk_index LIMIT ?";
        List<Object> args = new ArrayList<>();
        // SELECT 里的两处分词子查询，与 WHERE 的匹配子查询共用同一个查询文本。
        args.add(query);
        args.add(query);
        args.addAll(identifiers);
        args.addAll(fragment.args());
        args.add(query);
        // 覆盖率下限：查询词项集合 + 词项数量；精确编号命中直接放行。
        args.add(query);
        args.add(query);
        args.addAll(identifiers);
        args.add(limit);
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    private String currentMetadataSql() {
        return "(COALESCE(e.metadata, '{}'::jsonb) "
                + "|| COALESCE(d.metadata, '{}'::jsonb) "
                + "|| jsonb_build_object("
                + "'title', d.title, 'documentType', d.document_type)) "
                + "AS metadata";
    }

    private boolean isExcluded(Map<String, Object> row, List<Long> excludeIds) {
        if (excludeIds == null || excludeIds.isEmpty()) return false;
        Object embeddingId = row.get("embedding_id");
        if (embeddingId instanceof Number number) {
            return excludeIds.contains(number.longValue());
        }
        if (!row.containsKey("local_chunk_id")
                && row.get("id") instanceof Number number) {
            return excludeIds.contains(number.longValue());
        }
        return false;
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
