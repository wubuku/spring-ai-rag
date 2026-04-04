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
 * 审计日志仓库
 */
@Repository
public interface RagAuditLogRepository extends JpaRepository<RagAuditLog, Long> {

    /**
     * 查询指定实体的审计历史
     */
    Page<RagAuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            String entityType, String entityId, Pageable pageable);

    /**
     * 查询指定会话的审计记录
     */
    Page<RagAuditLog> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    /**
     * 查询指定操作类型的审计记录
     */
    Page<RagAuditLog> findByOperationOrderByCreatedAtDesc(String operation, Pageable pageable);

    /**
     * 查询时间范围内的审计记录
     */
    Page<RagAuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
            ZonedDateTime start, ZonedDateTime end, Pageable pageable);

    /**
     * 统计指定实体的操作次数
     */
    long countByEntityTypeAndEntityIdAndOperation(String entityType, String entityId, String operation);

    /**
     * 清理指定时间之前的审计日志
     */
    long deleteByCreatedAtBefore(ZonedDateTime cutoff);

    /**
     * 查询最近的操作记录（用于实时监控）
     */
    List<RagAuditLog> findTop20ByOrderByCreatedAtDesc();
}
