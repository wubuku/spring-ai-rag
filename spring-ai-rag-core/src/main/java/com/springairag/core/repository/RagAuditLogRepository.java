package com.springairag.core.repository;

import com.springairag.core.entity.RagAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
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

    @Query(value = """
            SELECT * FROM rag_audit_log
            WHERE (CAST(:operation AS text) IS NULL OR operation = CAST(:operation AS text))
              AND (CAST(:entityType AS text) IS NULL OR entity_type = CAST(:entityType AS text))
              AND (CAST(:entityId AS text) IS NULL OR entity_id = CAST(:entityId AS text))
              AND (CAST(:fromTime AS timestamptz) IS NULL
                   OR created_at >= CAST(:fromTime AS timestamptz))
              AND (CAST(:toTime AS timestamptz) IS NULL
                   OR created_at < CAST(:toTime AS timestamptz))
            ORDER BY created_at DESC, id DESC
            """,
            countQuery = """
            SELECT count(*) FROM rag_audit_log
            WHERE (CAST(:operation AS text) IS NULL OR operation = CAST(:operation AS text))
              AND (CAST(:entityType AS text) IS NULL OR entity_type = CAST(:entityType AS text))
              AND (CAST(:entityId AS text) IS NULL OR entity_id = CAST(:entityId AS text))
              AND (CAST(:fromTime AS timestamptz) IS NULL
                   OR created_at >= CAST(:fromTime AS timestamptz))
              AND (CAST(:toTime AS timestamptz) IS NULL
                   OR created_at < CAST(:toTime AS timestamptz))
            """,
            nativeQuery = true)
    Page<RagAuditLog> search(
            @Param("operation") String operation,
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("fromTime") ZonedDateTime fromTime,
            @Param("toTime") ZonedDateTime toTime,
            Pageable pageable);

    /**
     * Count operations for a specific entity.
     */
    long countByEntityTypeAndEntityIdAndOperation(String entityType, String entityId, String operation);

    /**
     * Delete audit logs before the specified time.
     */
    long deleteByCreatedAtBefore(ZonedDateTime cutoff);

    @Modifying
    @Query(value = """
            DELETE FROM rag_audit_log
            WHERE id IN (
                SELECT id FROM rag_audit_log
                WHERE created_at < :cutoff
                ORDER BY created_at ASC, id ASC
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteExpiredBatch(
            @Param("cutoff") ZonedDateTime cutoff,
            @Param("batchSize") int batchSize);

    /**
     * Query recent operation records (for real-time monitoring).
     */
    List<RagAuditLog> findTop20ByOrderByCreatedAtDesc();
}
