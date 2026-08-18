package com.springairag.core.repository;

import com.springairag.core.entity.RagRetrievalLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Retrieval Log Repository
 *
 * <p>Provides CRUD operations and statistical query capabilities for retrieval logs.
 */
@Repository
public interface RagRetrievalLogRepository extends JpaRepository<RagRetrievalLog, Long> {

    /**
     * Paginated query by time range.
     */
    Page<RagRetrievalLog> findByCreatedAtBetween(
            ZonedDateTime startDate, ZonedDateTime endDate, Pageable pageable);

    /**
     * Query by session ID.
     */
    List<RagRetrievalLog> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    /**
     * Query slow queries (ordered by total time descending).
     */
    @Query("SELECT l FROM RagRetrievalLog l WHERE l.createdAt BETWEEN :startDate AND :endDate " +
           "ORDER BY l.totalTimeMs DESC")
    Page<RagRetrievalLog> findSlowQueries(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate,
            Pageable pageable);

    /**
     * Count total logs in the specified time range.
     */
    long countByCreatedAtBetween(ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * Average total time.
     */
    @Query("SELECT AVG(l.totalTimeMs) FROM RagRetrievalLog l " +
           "WHERE l.createdAt BETWEEN :startDate AND :endDate")
    Double findAvgTotalTime(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * Average vector search time.
     */
    @Query("SELECT AVG(l.vectorSearchTimeMs) FROM RagRetrievalLog l " +
           "WHERE l.createdAt BETWEEN :startDate AND :endDate")
    Double findAvgVectorSearchTime(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * Average full-text search time.
     */
    @Query("SELECT AVG(l.fulltextSearchTimeMs) FROM RagRetrievalLog l " +
           "WHERE l.createdAt BETWEEN :startDate AND :endDate")
    Double findAvgFulltextSearchTime(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * Stats grouped by retrieval strategy.
     */
    @Query("SELECT l.retrievalStrategy, COUNT(l), AVG(l.totalTimeMs) FROM RagRetrievalLog l " +
           "WHERE l.createdAt BETWEEN :startDate AND :endDate " +
           "GROUP BY l.retrievalStrategy")
    List<Object[]> findStatsByStrategy(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * Aggregate average total time by day (for trend charts).
     */
    @Query(value = "SELECT DATE_TRUNC('day', created_at) as day, AVG(total_time_ms) as avg_time, COUNT(*) as cnt " +
           "FROM rag_retrieval_logs WHERE created_at BETWEEN :startDate AND :endDate " +
           "GROUP BY DATE_TRUNC('day', created_at) ORDER BY day",
           nativeQuery = true)
    List<Object[]> aggregateAvgTotalTimeByDay(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * Delete logs before the specified time.
     */
    @Modifying
    @Query("DELETE FROM RagRetrievalLog l WHERE l.createdAt < :cutoff")
    long deleteByCreatedAtBefore(@Param("cutoff") ZonedDateTime cutoff);

    Optional<RagRetrievalLog> findByTraceId(UUID traceId);

    @Query(value = """
            SELECT * FROM rag_retrieval_logs
            WHERE owner_principal_id = :owner
              AND trace_id IS NOT NULL
              AND (:operation IS NULL OR operation = :operation)
              AND (:outcomeCode IS NULL OR outcome_code = :outcomeCode)
              AND (:emptyReasonCode IS NULL OR empty_reason_code = :emptyReasonCode)
              AND (:sessionId IS NULL OR session_id = :sessionId)
              AND (:citationStatus IS NULL
                   OR metadata #>> '{citationValidation,status}' = :citationStatus)
            ORDER BY created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM rag_retrieval_logs
            WHERE owner_principal_id = :owner
              AND trace_id IS NOT NULL
              AND (:operation IS NULL OR operation = :operation)
              AND (:outcomeCode IS NULL OR outcome_code = :outcomeCode)
              AND (:emptyReasonCode IS NULL OR empty_reason_code = :emptyReasonCode)
              AND (:sessionId IS NULL OR session_id = :sessionId)
              AND (:citationStatus IS NULL
                   OR metadata #>> '{citationValidation,status}' = :citationStatus)
            """,
            nativeQuery = true)
    Page<RagRetrievalLog> searchTraces(
            @Param("owner") String owner,
            @Param("operation") String operation,
            @Param("outcomeCode") String outcomeCode,
            @Param("emptyReasonCode") String emptyReasonCode,
            @Param("sessionId") String sessionId,
            @Param("citationStatus") String citationStatus,
            Pageable pageable);

    @Modifying
    @Query(value = """
            DELETE FROM rag_retrieval_logs
            WHERE id IN (
                SELECT id FROM rag_retrieval_logs
                WHERE trace_id IS NOT NULL
                  AND created_at < :cutoff
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteExpiredTraces(
            @Param("cutoff") ZonedDateTime cutoff,
            @Param("batchSize") int batchSize);
}
