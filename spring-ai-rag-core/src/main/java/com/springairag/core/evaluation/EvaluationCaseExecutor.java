package com.springairag.core.evaluation;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalBranchStage;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.retrieval.HybridRetrieverService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 用当前 HybridRetriever 执行 suite case，再把 document ID 解析为稳定身份。
 */
@Component
public class EvaluationCaseExecutor {

    private final HybridRetrieverService retrieverService;
    private final ReRankingService rerankingService;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public EvaluationCaseExecutor(
            HybridRetrieverService retrieverService,
            ReRankingService rerankingService,
            JdbcTemplate jdbcTemplate) {
        this.retrieverService = retrieverService;
        this.rerankingService = rerankingService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public EvaluationCaseExecutor(
            HybridRetrieverService retrieverService,
            JdbcTemplate jdbcTemplate) {
        this(retrieverService, null, jdbcTemplate);
    }

    public Executed search(
            String query,
            RetrievalScope scope,
            RetrievalConfig config,
            RetrievalFilters filters) {
        RetrievalOutcome outcome = retrieverService.searchInScopeDetailed(
                query, scope, List.of(), config.getMaxResults(), config, filters);
        if (config.isUseRerank() && !outcome.results().isEmpty()) {
            if (rerankingService == null) {
                throw new IllegalStateException("ReRankingService is required for rerank evaluation");
            }
            long startedAt = System.nanoTime();
            List<RetrievalResult> beforeRerank = outcome.results();
            List<RetrievalResult> ranked = rerankingService.rerank(
                    query, beforeRerank, config.getMaxResults());
            outcome = outcome.withRerank(
                    new RetrievalBranchStage(
                            RetrievalBranchStage.RERANK,
                            "rerank",
                            RetrievalBranchStage.SUCCESS,
                            (System.nanoTime() - startedAt) / 1_000_000L,
                            beforeRerank.size(),
                            ranked.size(),
                            null),
                    ranked,
                    false);
        }
        List<EvaluationSuiteDefinition.Identity> identities = lookup(
                outcome.results());
        return new Executed(identities, outcome.traceId(), outcome.elapsedMs());
    }

    public List<EvaluationSuiteDefinition.Identity> lookup(
            List<RetrievalResult> results) {
        List<Long> ids = new ArrayList<>();
        for (RetrievalResult result : results) {
            try {
                ids.add(Long.parseLong(result.getDocumentId()));
            } catch (NumberFormatException ignored) {
                // 非数字 documentId 不能映射到受管身份
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        Map<Long, EvaluationSuiteDefinition.Identity> byId = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT d.id, c.collection_key, d.source_namespace, d.external_id "
                        + "FROM rag_documents d "
                        + "JOIN rag_collection c ON c.id = d.collection_id "
                        + "WHERE d.id IN (" + placeholders + ")",
                rs -> {
                    byId.put(rs.getLong("id"), new EvaluationSuiteDefinition.Identity(
                            rs.getString("collection_key"),
                            rs.getString("source_namespace"),
                            rs.getString("external_id")));
                },
                ids.toArray());
        List<EvaluationSuiteDefinition.Identity> ordered = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Long id : ids) {
            EvaluationSuiteDefinition.Identity identity = byId.get(id);
            if (identity != null && identity.externalId() != null
                    && !identity.externalId().isBlank()
                    && seen.add(stableIdentity(identity))) {
                ordered.add(identity);
            }
        }
        return ordered;
    }

    public boolean identityExists(String collectionKey, String externalId) {
        return identityExists(collectionKey, "default", externalId);
    }

    public boolean identityExists(
            String collectionKey,
            String sourceNamespace,
            String externalId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM rag_documents d
                JOIN rag_collection c ON c.id = d.collection_id
                WHERE c.collection_key = ?
                  AND d.source_namespace = ?
                  AND d.external_id = ?
                  AND d.enabled = true
                """,
                Integer.class, collectionKey, sourceNamespace, externalId);
        return count != null && count > 0;
    }

    static String stableIdentity(
            EvaluationSuiteDefinition.Identity identity) {
        return identity.collectionKey() + "\0"
                + identity.sourceNamespace() + "\0"
                + identity.externalId();
    }

    public Map<String, Object> collectionSnapshot(List<String> collectionKeys) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String key : collectionKeys) {
            jdbcTemplate.query("""
                    SELECT COUNT(*) FILTER (WHERE d.enabled) AS enabled_count,
                           MAX(d.updated_at) AS max_updated_at
                    FROM rag_documents d
                    JOIN rag_collection c ON c.id = d.collection_id
                    WHERE c.collection_key = ?
                    """,
                    rs -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("enabledDocuments", rs.getLong("enabled_count"));
                        item.put("maxUpdatedAt", rs.getTimestamp("max_updated_at") == null
                                ? null : rs.getTimestamp("max_updated_at").toInstant().toString());
                        snapshot.put(key, item);
                    },
                    key);
        }
        return snapshot;
    }

    public record Executed(
            List<EvaluationSuiteDefinition.Identity> identities,
            java.util.UUID traceId,
            long latencyMs) {
    }
}
