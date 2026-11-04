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
 * Alert Record Repository
 */
@Repository
public interface AlertRepository extends JpaRepository<RagAlert, Long> {

    /**
     * Query alert history by time range and filter conditions. 排序由调用的
     * Pageable 提供（firedAt desc, id desc），且 limit 有调用方上限。
     */
    @Query("SELECT a FROM RagAlert a WHERE a.firedAt BETWEEN :startDate AND :endDate " +
           "AND (:severity IS NULL OR a.severity = :severity) " +
           "AND (:alertType IS NULL OR a.alertType = :alertType)")
    List<RagAlert> findAlertHistory(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate,
            @Param("severity") String severity,
            @Param("alertType") String alertType,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Get active alerts (unresolved), bounded by the caller's Pageable.
     *
     * <p>未解决告警会持续累积，不能无界返回；排序由 Pageable 提供
     * （firedAt desc, id desc）。
     */
    @Query("SELECT a FROM RagAlert a WHERE a.status = :status")
    List<RagAlert> findActiveAlerts(
            @Param("status") String status,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Count alerts by severity.
     */
    @Query("SELECT COUNT(a) FROM RagAlert a WHERE a.firedAt BETWEEN :startDate AND :endDate " +
           "AND a.severity = :severity")
    long countBySeverity(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate,
            @Param("severity") String severity);

    /**
     * Count active alerts.
     */
    @Query("SELECT COUNT(a) FROM RagAlert a WHERE a.firedAt BETWEEN :startDate AND :endDate " +
           "AND a.status = 'ACTIVE'")
    long countActiveAlerts(
            @Param("startDate") ZonedDateTime startDate,
            @Param("endDate") ZonedDateTime endDate);

    /**
     * Count total alerts.
     */
    long countByFiredAtBetween(ZonedDateTime startDate, ZonedDateTime endDate);

    /**
     * Delete old resolved alert records.
     */
    @Modifying
    @Query("DELETE FROM RagAlert a WHERE a.status = 'RESOLVED' AND a.resolvedAt < :cutoff")
    void deleteOldResolvedAlerts(@Param("cutoff") ZonedDateTime cutoff);
}
