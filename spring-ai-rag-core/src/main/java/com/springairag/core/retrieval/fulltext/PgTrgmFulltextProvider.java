package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.RetrievalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * pg_trgm 全文检索策略
 *
 * <p>使用 PostgreSQL pg_trgm 扩展的 `similarity()` 和 `word_similarity()` 函数
 * 做模糊文本匹配，支持多词查询（取所有词的最大相似度）。
 *
 * <p>对中文支持有限（字符三元组，非语义分词），
 * 适合英文场景或作为 pg_jieba 不可用时的降级方案。
 */
public class PgTrgmFulltextProvider implements FulltextSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(PgTrgmFulltextProvider.class);

    private final JdbcTemplate jdbcTemplate;
    private final boolean available;

    public PgTrgmFulltextProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.available = detectAvailability();
        if (available) {
            log.info("pg_trgm full-text search provider initialized");
        }
    }

    private boolean detectAvailability() {
        try {
            jdbcTemplate.queryForObject(
                    "SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class);
            return true;
        } catch (Exception e) { // Availability detection: return false gracefully
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

        String[] keywords = query.trim().split("\\s+");
        if (keywords.length == 0) return Collections.emptyList();

        try {
            // 对每个关键词分别检索，取最高相似度
            Map<Long, double[]> idToBestScore = new LinkedHashMap<>();
            Map<Long, Map<String, Object>> idToRow = new HashMap<>();

            for (String keyword : keywords) {
                if (keyword.isBlank()) continue;
                List<Map<String, Object>> rows = executeQuery(keyword, documentIds, limit);
                for (Map<String, Object> row : rows) {
                    long id = ((Number) row.get("id")).longValue();
                    double sim = ((Number) row.get("sim")).doubleValue();
                    double[] best = idToBestScore.get(id);
                    if (best == null || sim > best[0]) {
                        idToBestScore.put(id, new double[]{sim});
                        idToRow.put(id, row);
                    }
                }
            }

            // 转换为 RetrievalResult，过滤排除项和最低分数
            return idToBestScore.entrySet().stream()
                    .filter(e -> !isExcluded(e.getKey(), excludeIds))
                    .filter(e -> e.getValue()[0] >= minScore)
                    .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                    .limit(limit)
                    .map(e -> {
                        Map<String, Object> row = idToRow.get(e.getKey());
                        double sim = e.getValue()[0];
                        return toResult(row, sim);
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) { // Resilience: return empty on search failure
            log.warn("pg_trgm search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> executeQuery(String searchTerm,
                                                    List<Long> documentIds, int limit) {
        if (documentIds != null && !documentIds.isEmpty()) {
            String placeholders = documentIds.stream()
                    .map(id -> "?").collect(Collectors.joining(","));
            String sql = String.format(
                    "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata, " +
                            "similarity(chunk_text, ?) as sim FROM rag_embeddings " +
                            "WHERE document_id IN (%s) AND similarity(chunk_text, ?) > 0.1 " +
                            "ORDER BY sim DESC LIMIT ?",
                    placeholders);
            List<Object> args = new ArrayList<>(documentIds);
            args.add(searchTerm);
            args.add(searchTerm);
            args.add(limit);
            return jdbcTemplate.queryForList(sql, args.toArray());
        }
        return jdbcTemplate.queryForList(
                "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata, " +
                        "similarity(chunk_text, ?) as sim FROM rag_embeddings " +
                        "WHERE similarity(chunk_text, ?) > 0.1 ORDER BY sim DESC LIMIT ?",
                searchTerm, searchTerm, limit);
    }

    private boolean isExcluded(long id, List<Long> excludeIds) {
        return excludeIds != null && excludeIds.contains(id);
    }

    private RetrievalResult toResult(Map<String, Object> row, double sim) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(String.valueOf(row.get("document_id")));
        r.setChunkText((String) row.get("chunk_text"));
        r.setScore(sim);
        r.setVectorScore(0.0);
        r.setFulltextScore(sim);
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
