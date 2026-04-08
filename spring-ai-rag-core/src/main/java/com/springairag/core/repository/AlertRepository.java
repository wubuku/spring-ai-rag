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
     * Query alert history by time range and filter conditions.
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
     * Get active alerts (unresolved).
     */
    List<RagAlert> findByStatusOrderByFiredAtDesc(String status);

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
