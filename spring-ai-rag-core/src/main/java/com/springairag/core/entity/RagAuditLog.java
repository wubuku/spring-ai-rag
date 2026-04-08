package com.springairag.core.entity;

import jakarta.persistence.*;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Audit log entity.
 *
 * <p>Records audit trails of key business operations including create, update, delete.
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
     * Operation type: CREATE / UPDATE / DELETE / READ
     */
    @Column(name = "operation", nullable = false, length = 16)
    private String operation;

    /**
     * Entity type: e.g. Collection, Document, ChatHistory
     */
    @Column(name = "entity_type", nullable = false, length = 64)
    private String entityType;

    /**
     * Entity ID
     */
    @Column(name = "entity_id", nullable = false, length = 64)
    private String entityId;

    /**
     * Session ID (nullable)
     */
    @Column(name = "session_id", length = 128)
    private String sessionId;

    /**
     * Operator identifier (e.g. API key prefix, user ID — nullable)
     */
    @Column(name = "operator", length = 128)
    private String operator;

    /**
     * Operation description (e.g. "Created collection test-collection")
     */
    @Column(name = "description", length = 512)
    private String description;

    /**
     * Operation details (JSON format, stores before/after content, etc.)
     */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    /**
     * Client IP address
     */
    @Column(name = "client_ip", length = 45)
    private String clientIp;

    /**
     * Request trace ID
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
