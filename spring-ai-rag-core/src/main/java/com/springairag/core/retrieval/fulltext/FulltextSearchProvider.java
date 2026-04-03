package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;

import java.util.List;

/**
 * 全文检索策略接口
 *
 * <p>抽象不同的全文检索后端（pg_trgm、pg_jieba 等），
 * 由 {@link FulltextSearchProviderFactory} 根据数据库扩展可用性自动选择。
 *
 * <p>实现要求：
 * <ul>
 *   <li>不可用时应返回空列表，不应抛出异常</li>
 *   <li>结果通过 {@link RetrievalResult#setFulltextScore(double)} 设置相关分数</li>
 * </ul>
 */
public interface FulltextSearchProvider {

    /**
     * 提供者名称（用于日志和配置）
     */
    String getName();

    /**
     * 是否可用（启动时检测，结果缓存）
     */
    boolean isAvailable();

    /**
     * 执行全文检索
     *
     * @param query        查询文本
     * @param documentIds  限定文档ID（null 表示搜索全部）
     * @param excludeIds   排除的嵌入ID
     * @param limit        返回结果数量
     * @param minScore     最低相关分数阈值
     * @return 检索结果列表（按相关度降序）
     */
    List<RetrievalResult> search(String query, List<Long> documentIds,
                                 List<Long> excludeIds, int limit, double minScore);
}
