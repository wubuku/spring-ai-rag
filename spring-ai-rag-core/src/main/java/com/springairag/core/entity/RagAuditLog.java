package com.springairag.core.entity;

import jakarta.persistence.*;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * 审计日志实体
 *
 * <p>记录关键业务操作的审计轨迹，包括创建、更新、删除等操作。
 */
@Entity
@Table(name = "rag_audit_log", indexes = {
        @Index(name = "idx_audit_entity_type_id", columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_operation", columnList = "operation"),
        @Index(name = "idx_audit_session", columnList = "session_id"),
        @Index(name = "idx_audit_created_at", columnList = "created_at")
})
public class RagAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 操作类型：CREATE / UPDATE / DELETE / READ
     */
    @Column(name = "operation", nullable = false, length = 16)
    private String operation;

    /**
     * 实体类型：如 Collection、Document、ChatHistory 等
     */
    @Column(name = "entity_type", nullable = false, length = 64)
    private String entityType;

    /**
     * 实体 ID
     */
    @Column(name = "entity_id", nullable = false, length = 64)
    private String entityId;

    /**
     * 会话 ID（可为 null）
     */
    @Column(name = "session_id", length = 128)
    private String sessionId;

    /**
     * 操作人标识（如 API Key 前缀、用户 ID，可为 null）
     */
    @Column(name = "operator", length = 128)
    private String operator;

    /**
     * 操作描述（如 "创建集合 test-collection"）
     */
    @Column(name = "description", length = 512)
    private String description;

    /**
     * 操作详情（JSON 格式，存储变更前后内容等）
     */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    /**
     * 客户端 IP 地址
     */
    @Column(name = "client_ip", length = 45)
    private String clientIp;

    /**
     * 请求追踪 ID
     */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = ZonedDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public ZonedDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(ZonedDateTime createdAt) { this.createdAt = createdAt; }
}
