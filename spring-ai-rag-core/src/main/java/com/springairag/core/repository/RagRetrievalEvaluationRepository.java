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
 * Retrieval Evaluation Record Repository
 */
@Repository
public interface RagRetrievalEvaluationRepository extends JpaRepository<RagRetrievalEvaluation, Long> {

    /**
     * Query by time range (ordered by creation time descending).
     */
    List<RagRetrievalEvaluation> findByCreatedAtBetweenOrderByCreatedAtDesc(
            ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * Paginated query (ordered by creation time descending).
     */
    Page<RagRetrievalEvaluation> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Count evaluations in the specified time range.
     */
    long countByCreatedAtBetween(ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * Average MRR.
     */
    @Query("SELECT AVG(e.mrr) FROM RagRetrievalEvaluation e " +
           "WHERE e.createdAt BETWEEN :startDate AND :endDate AND e.mrr IS NOT NULL")
    Double findAvgMrr(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * Average NDCG.
     */
    @Query("SELECT AVG(e.ndcg) FROM RagRetrievalEvaluation e " +
           "WHERE e.createdAt BETWEEN :startDate AND :endDate AND e.ndcg IS NOT NULL")
    Double findAvgNdcg(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * Average Hit Rate.
     */
    @Query("SELECT AVG(e.hitRate) FROM RagRetrievalEvaluation e " +
           "WHERE e.createdAt BETWEEN :startDate AND :endDate AND e.hitRate IS NOT NULL")
    Double findAvgHitRate(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);
}
