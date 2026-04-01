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

/**
 * 检索日志仓库
 *
 * <p>提供检索日志的 CRUD 操作和统计查询能力。
 */
@Repository
public interface RagRetrievalLogRepository extends JpaRepository<RagRetrievalLog, Long> {

    /**
     * 按时间范围分页查询
     */
    Page<RagRetrievalLog> findByCreatedAtBetween(
            ZonedDateTime startDate, ZonedDateTime endDate, Pageable pageable);

    /**
     * 按会话 ID 查询
     */
    List<RagRetrievalLog> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    /**
     * 查询慢查询（按总耗时降序）
     */
    @Query("SELECT l FROM RagRetrievalLog l WHERE l.createdAt BETWEEN :startDate AND :endDate " +
           "ORDER BY l.totalTimeMs DESC")
    Page<RagRetrievalLog> findSlowQueries(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate,
            Pageable pageable);

    /**
     * 统计指定时间段的总日志数
     */
    long countByCreatedAtBetween(ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * 平均总耗时
     */
    @Query("SELECT AVG(l.totalTimeMs) FROM RagRetrievalLog l " +
           "WHERE l.createdAt BETWEEN :startDate AND :endDate")
    Double findAvgTotalTime(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * 平均向量检索耗时
     */
    @Query("SELECT AVG(l.vectorSearchTimeMs) FROM RagRetrievalLog l " +
           "WHERE l.createdAt BETWEEN :startDate AND :endDate")
    Double findAvgVectorSearchTime(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * 平均全文检索耗时
     */
    @Query("SELECT AVG(l.fulltextSearchTimeMs) FROM RagRetrievalLog l " +
           "WHERE l.createdAt BETWEEN :startDate AND :endDate")
    Double findAvgFulltextSearchTime(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * 按检索策略分组统计
     */
    @Query("SELECT l.retrievalStrategy, COUNT(l), AVG(l.totalTimeMs) FROM RagRetrievalLog l " +
           "WHERE l.createdAt BETWEEN :startDate AND :endDate " +
           "GROUP BY l.retrievalStrategy")
    List<Object[]> findStatsByStrategy(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * 按天聚合平均总耗时（趋势图）
     */
    @Query(value = "SELECT DATE_TRUNC('day', created_at) as day, AVG(total_time_ms) as avg_time, COUNT(*) as cnt " +
           "FROM rag_retrieval_logs WHERE created_at BETWEEN :startDate AND :endDate " +
           "GROUP BY DATE_TRUNC('day', created_at) ORDER BY day",
           nativeQuery = true)
    List<Object[]> aggregateAvgTotalTimeByDay(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * 清理指定时间之前的日志
     */
    @Modifying
    @Query("DELETE FROM RagRetrievalLog l WHERE l.createdAt < :cutoff")
    long deleteByCreatedAtBefore(@Param("cutoff") ZonedDateTime cutoff);
}
