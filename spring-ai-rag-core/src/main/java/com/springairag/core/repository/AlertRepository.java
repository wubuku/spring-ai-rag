package com.springairag.core.repository;

import com.springairag.core.entity.RagAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 告警记录仓库
 */
@Repository
public interface AlertRepository extends JpaRepository<RagAlert, Long> {

    /**
     * 按时间范围和过滤条件查询告警历史
     */
    @Query("SELECT a FROM RagAlert a WHERE a.firedAt BETWEEN :startDate AND :endDate " +
           "AND (:severity IS NULL OR a.severity = :severity) " +
           "AND (:alertType IS NULL OR a.alertType = :alertType) " +
           "ORDER BY a.firedAt DESC")
    List<RagAlert> findAlertHistory(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate,
            @Param("severity") String severity,
            @Param("alertType") String alertType);

    /**
     * 获取活跃告警（未解决的）
     */
    List<RagAlert> findByStatusOrderByFiredAtDesc(String status);

    /**
     * 按严重程度统计告警数量
     */
    @Query("SELECT COUNT(a) FROM RagAlert a WHERE a.firedAt BETWEEN :startDate AND :endDate " +
           "AND a.severity = :severity")
    long countBySeverity(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate,
            @Param("severity") String severity);

    /**
     * 统计活跃告警数量
     */
    @Query("SELECT COUNT(a) FROM RagAlert a WHERE a.firedAt BETWEEN :startDate AND :endDate " +
           "AND a.status = 'ACTIVE'")
    long countActiveAlerts(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * 统计总告警数量
     */
    long countByFiredAtBetween(ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * 删除已解决的旧告警记录
     */
    @Modifying
    @Query("DELETE FROM RagAlert a WHERE a.status = 'RESOLVED' AND a.resolvedAt < :cutoff")
    void deleteOldResolvedAlerts(@Param("cutoff") ZonedDateTime cutoff);
}
