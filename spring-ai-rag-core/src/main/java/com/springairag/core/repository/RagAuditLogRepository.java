package com.springairag.core.repository;

import com.springairag.core.entity.RagAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Audit Log Repository
 */
@Repository
public interface RagAuditLogRepository extends JpaRepository<RagAuditLog, Long> {

    /**
     * Query audit history for a specific entity.
     */
    Page<RagAuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            String entityType, String entityId, Pageable pageable);

    /**
     * Query audit records for a specific session.
     */
    Page<RagAuditLog> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    /**
     * Query audit records for a specific operation type.
     */
    Page<RagAuditLog> findByOperationOrderByCreatedAtDesc(String operation, Pageable pageable);

    /**
     * Query audit records within a time range.
     */
    Page<RagAuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
            ZonedDateTime start, ZonedDateTime end, Pageable pageable);

    /**
     * Count operations for a specific entity.
     */
    long countByEntityTypeAndEntityIdAndOperation(String entityType, String entityId, String operation);

    /**
     * Delete audit logs before the specified time.
     */
    long deleteByCreatedAtBefore(ZonedDateTime cutoff);

    /**
     * Query recent operation records (for real-time monitoring).
     */
    List<RagAuditLog> findTop20ByOrderByCreatedAtDesc();
}
