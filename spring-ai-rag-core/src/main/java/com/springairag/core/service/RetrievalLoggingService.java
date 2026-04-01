package com.springairag.core.service;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.entity.RagRetrievalLog;
import com.springairag.core.repository.RagRetrievalLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索日志服务
 *
 * <p>记录每次检索的性能数据，包括向量检索耗时、全文检索耗时、重排耗时、总耗时、结果数量和得分。
 * 通过 {@link ConditionalOnBean} 条件注册，仅在 RagRetrievalLogRepository 可用时创建。
 */
@Service
@ConditionalOnBean(RagRetrievalLogRepository.class)
public class RetrievalLoggingService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalLoggingService.class);

    private final RagRetrievalLogRepository repository;

    @Autowired
    public RetrievalLoggingService(RagRetrievalLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录检索操作
     *
     * @param sessionId           会话 ID（可为 null）
     * @param query               查询文本
     * @param strategy            检索策略（hybrid/vector/fulltext）
     * @param vectorSearchTimeMs  向量检索耗时
     * @param fulltextSearchTimeMs 全文检索耗时
     * @param rerankTimeMs        重排序耗时
     * @param results             检索结果列表
     */
    public void logRetrieval(String sessionId, String query, String strategy,
                             long vectorSearchTimeMs, long fulltextSearchTimeMs,
                             long rerankTimeMs, List<RetrievalResult> results) {
        try {
            RagRetrievalLog entry = new RagRetrievalLog();
            entry.setSessionId(sessionId);
            entry.setQuery(query);
            entry.setRetrievalStrategy(strategy);
            entry.setVectorSearchTimeMs(vectorSearchTimeMs);
            entry.setFulltextSearchTimeMs(fulltextSearchTimeMs);
            entry.setRerankTimeMs(rerankTimeMs);
            entry.setTotalTimeMs(vectorSearchTimeMs + fulltextSearchTimeMs + rerankTimeMs);
            entry.setResultCount(results != null ? results.size() : 0);

            // 提取结果得分
            if (results != null && !results.isEmpty()) {
                Map<String, Object> scores = new LinkedHashMap<>();
                for (int i = 0; i < results.size(); i++) {
                    RetrievalResult r = results.get(i);
                    String key = r.getDocumentId() != null ? r.getDocumentId() : "idx_" + i;
                    scores.put(key, r.getScore());
                }
                entry.setResultScores(scores);
            }

            repository.save(entry);
            log.debug("[RetrievalLogging] 已记录检索日志: query=\"{}\", strategy={}, total={}ms, results={}",
                    query, strategy, entry.getTotalTimeMs(), entry.getResultCount());
        } catch (Exception e) {
            // 日志记录失败不应影响正常业务流程
            log.warn("[RetrievalLogging] 记录检索日志失败: {}", e.getMessage());
        }
    }

    /**
     * 查询指定时间段的平均总耗时
     */
    public Double getAvgTotalTime(ZonedDateTime start, ZonedDateTime end) {
        return repository.findAvgTotalTime(start, end);
    }

    /**
     * 查询指定时间段的日志总数
     */
    public long count(ZonedDateTime start, ZonedDateTime end) {
        return repository.countByCreatedAtBetween(start, end);
    }

    /**
     * 清理指定时间之前的日志
     *
     * @param cutoff 截止时间
     * @return 删除的记录数
     */
    public long cleanup(ZonedDateTime cutoff) {
        return repository.deleteByCreatedAtBefore(cutoff);
    }
}
