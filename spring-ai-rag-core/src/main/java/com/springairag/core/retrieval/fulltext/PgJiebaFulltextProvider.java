package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * pg_jieba 中文分词全文检索策略
 *
 * <p>使用 PostgreSQL pg_jieba 扩展的 `jiebacfg` 文本搜索配置，
 * 通过 `to_tsvector('jiebacfg', text)` + `plainto_tsquery('jiebacfg', query)`
 * 实现真正的中文分词全文检索。
 *
 * <p>相比 pg_trgm 的字符三元组匹配，jieba 分词能正确识别中文词语边界，
 * 检索精度显著提升。
 *
 * <p>前提条件：
 * <ul>
 *   <li>数据库安装了 pg_jieba 扩展</li>
 *   <li>V1 迁移成功创建了 jiebacfg 文本搜索配置</li>
 *   <li>rag_embeddings 表有 ts_vector 类型的索引列（未来优化）</li>
 * </ul>
 *
 * <p>当前实现使用运行时 `to_tsvector()` 计算，不依赖预建索引。
 * 对于大数据集建议添加 GIN 索引列以提升性能。
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
            log.info("pg_jieba full-text search provider initialized (Chinese segmentation enabled)");
        }
    }

    private boolean detectAvailability() {
        try {
            // 检测 pg_jieba 扩展
            jdbcTemplate.queryForObject(
                    "SELECT 1 FROM pg_extension WHERE extname = 'pg_jieba'", Integer.class);
            // 检测 jiebacfg 配置
            jdbcTemplate.queryForObject(
                    "SELECT 1 FROM pg_ts_config WHERE cfgname = 'jiebacfg'", Integer.class);
            return true;
        } catch (Exception e) { // Health probe: must never throw, graceful degradation
            log.debug("pg_jieba not available: {}", e.getMessage());
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
            return rows.stream()
                    .filter(row -> !isExcluded(row, excludeIds))
                    .map(row -> {
                        double rank = ((Number) row.get("rank")).doubleValue();
                        return toResult(row, rank);
                    })
                    .filter(r -> r.getScore() >= minScore)
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (Exception e) { // Resilience: return empty on search failure
            log.warn("pg_jieba search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> executeSearch(String query, List<Long> documentIds, int limit) {
        // 使用 plainto_tsquery 做分词查询，ts_rank 做相关度排序
        if (documentIds != null && !documentIds.isEmpty()) {
            String placeholders = documentIds.stream()
                    .map(id -> "?").collect(Collectors.joining(","));
            String sql = String.format(
                    "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata, " +
                            "ts_rank(to_tsvector('%s', chunk_text), plainto_tsquery('%s', ?)) as rank " +
                            "FROM rag_embeddings " +
                            "WHERE document_id IN (%s) " +
                            "AND to_tsvector('%s', chunk_text) @@ plainto_tsquery('%s', ?) " +
                            "ORDER BY rank DESC LIMIT ?",
                    TS_CONFIG, TS_CONFIG, placeholders, TS_CONFIG, TS_CONFIG);
            List<Object> args = new ArrayList<>();
            args.add(query);  // ts_rank 参数
            args.addAll(documentIds);
            args.add(query);  // @@ 匹配参数
            args.add(limit);
            return jdbcTemplate.queryForList(sql, args.toArray());
        }

        String sql = String.format(
                "SELECT id, chunk_text, embedding, document_id, chunk_index, metadata, " +
                        "ts_rank(to_tsvector('%s', chunk_text), plainto_tsquery('%s', ?)) as rank " +
                        "FROM rag_embeddings " +
                        "WHERE to_tsvector('%s', chunk_text) @@ plainto_tsquery('%s', ?) " +
                        "ORDER BY rank DESC LIMIT ?",
                TS_CONFIG, TS_CONFIG, TS_CONFIG, TS_CONFIG);
        return jdbcTemplate.queryForList(sql, query, query, limit);
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
