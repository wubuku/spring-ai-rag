package com.springairag.core.evaluation;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.retrieval.HybridRetrieverService;
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
    private final JdbcTemplate jdbcTemplate;

    public EvaluationCaseExecutor(
            HybridRetrieverService retrieverService,
            JdbcTemplate jdbcTemplate) {
        this.retrieverService = retrieverService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Executed search(
            String query,
            RetrievalScope scope,
            RetrievalConfig config,
            RetrievalFilters filters) {
        RetrievalOutcome outcome = retrieverService.searchInScopeDetailed(
                query, scope, List.of(), config.getMaxResults(), config, filters);
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
                "SELECT d.id, c.collection_key, d.external_id "
                        + "FROM rag_documents d "
                        + "JOIN rag_collection c ON c.id = d.collection_id "
                        + "WHERE d.id IN (" + placeholders + ")",
                rs -> {
                    byId.put(rs.getLong("id"), new EvaluationSuiteDefinition.Identity(
                            rs.getString("collection_key"),
                            rs.getString("external_id")));
                },
                ids.toArray());
        List<EvaluationSuiteDefinition.Identity> ordered = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Long id : ids) {
            EvaluationSuiteDefinition.Identity identity = byId.get(id);
            if (identity != null && identity.externalId() != null
                    && !identity.externalId().isBlank()
                    && seen.add(identity.collectionKey() + "\0" + identity.externalId())) {
                ordered.add(identity);
            }
        }
        return ordered;
    }

    public boolean identityExists(String collectionKey, String externalId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM rag_documents d
                JOIN rag_collection c ON c.id = d.collection_id
                WHERE c.collection_key = ?
                  AND d.external_id = ?
                  AND d.enabled = true
                """,
                Integer.class, collectionKey, externalId);
        return count != null && count > 0;
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
