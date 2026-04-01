package com.springairag.core.repository;

import com.springairag.core.entity.RagRetrievalEvaluation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 检索评估记录仓库
 */
@Repository
public interface RagRetrievalEvaluationRepository extends JpaRepository<RagRetrievalEvaluation, Long> {

    /**
     * 按时间范围查询（按创建时间降序）
     */
    List<RagRetrievalEvaluation> findByCreatedAtBetweenOrderByCreatedAtDesc(
            ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * 分页查询（按创建时间降序）
     */
    Page<RagRetrievalEvaluation> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 统计指定时间段的评估数
     */
    long countByCreatedAtBetween(ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * 平均 MRR
     */
    @Query("SELECT AVG(e.mrr) FROM RagRetrievalEvaluation e " +
           "WHERE e.createdAt BETWEEN :startDate AND :endDate AND e.mrr IS NOT NULL")
    Double findAvgMrr(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * 平均 NDCG
     */
    @Query("SELECT AVG(e.ndcg) FROM RagRetrievalEvaluation e " +
           "WHERE e.createdAt BETWEEN :startDate AND :endDate AND e.ndcg IS NOT NULL")
    Double findAvgNdcg(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * 平均 Hit Rate
     */
    @Query("SELECT AVG(e.hitRate) FROM RagRetrievalEvaluation e " +
           "WHERE e.createdAt BETWEEN :startDate AND :endDate AND e.hitRate IS NOT NULL")
    Double findAvgHitRate(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);
}
