package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.entity.RagAuditLog;
import com.springairag.core.repository.RagAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Audit Log Service
 *
 * <p>Provides structured audit logging for critical business operations (create/update/delete).
 * Registered conditionally via {@link ConditionalOnBean} — only created when RagAuditLogRepository is available.
 *
 * <p>Audit events inject traceId via MDC and obtain clientIp from HttpServletRequest.
 * Failures do not block the business flow (resilience mode).
 */
@Service
@ConditionalOnBean(RagAuditLogRepository.class)
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    /** Operation type: create */
    public static final String OP_CREATE = "CREATE";
    /** Operation type: update */
    public static final String OP_UPDATE = "UPDATE";
    /** Operation type: delete */
    public static final String OP_DELETE = "DELETE";
    /** Operation type: import */
    public static final String OP_IMPORT = "IMPORT";
    /** Operation type: embed */
    public static final String OP_EMBED = "EMBED";
    /** Operation type: clear cache */
    public static final String OP_CACHE_CLEAR = "CACHE_CLEAR";
    /** Operation type: fire alert */
    public static final String OP_ALERT_FIRE = "ALERT_FIRE";
    /** Operation type: resolve alert */
    public static final String OP_ALERT_RESOLVE = "ALERT_RESOLVE";
    /** Operation type: silence alert */
    public static final String OP_ALERT_SILENCE = "ALERT_SILENCE";

    /** Entity type: collection */
    public static final String ENTITY_COLLECTION = "Collection";
    /** Entity type: document */
    public static final String ENTITY_DOCUMENT = "Document";
    /** Entity type: chat history */
    public static final String ENTITY_CHAT_HISTORY = "ChatHistory";
    /** Entity type: A/B experiment */
    public static final String ENTITY_AB_TEST = "AbTest";
    /** Entity type: alert */
    public static final String ENTITY_ALERT = "Alert";
    /** Entity type: SLO config */
    public static final String ENTITY_SLO_CONFIG = "SloConfig";
    /** Entity type: silence schedule */
    public static final String ENTITY_SILENCE_SCHEDULE = "SilenceSchedule";
    /** Entity type: embed cache */
    public static final String ENTITY_EMBED_CACHE = "EmbedCache";
    /** Entity type: user feedback */
    public static final String ENTITY_USER_FEEDBACK = "UserFeedback";

    private final RagAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Autowired
    public AuditLogService(RagAuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Log a create operation.
     */
    public void logCreate(String entityType, String entityId, String description) {
        logAudit(OP_CREATE, entityType, entityId, description, null, null);
    }

    /**
     * Log a create operation with extra details.
     */
    public void logCreate(String entityType, String entityId, String description, Map<String, Object> details) {
        logAudit(OP_CREATE, entityType, entityId, description, details, null);
    }

    /**
     * Log an update operation.
     */
    public void logUpdate(String entityType, String entityId, String description) {
        logAudit(OP_UPDATE, entityType, entityId, description, null, null);
    }

    /**
     * Log an update operation with extra details.
     */
    public void logUpdate(String entityType, String entityId, String description, Map<String, Object> details) {
        logAudit(OP_UPDATE, entityType, entityId, description, details, null);
    }

    /**
     * Log a delete operation.
     */
    public void logDelete(String entityType, String entityId, String description) {
        logAudit(OP_DELETE, entityType, entityId, description, null, null);
    }

    /**
     * Log a delete operation with extra details.
     */
    public void logDelete(String entityType, String entityId, String description, Map<String, Object> details) {
        logAudit(OP_DELETE, entityType, entityId, description, details, null);
    }

    private void logAudit(String operation, String entityType, String entityId,
                          String description, Map<String, Object> details, String sessionId) {
        try {
            RagAuditLog entry = new RagAuditLog();
            entry.setOperation(operation);
            entry.setEntityType(entityType);
            entry.setEntityId(entityId);
            entry.setDescription(description);
            entry.setSessionId(sessionId);
            entry.setTraceId(MDC.get("traceId"));
            entry.setDetails(toJson(details));

            repository.save(entry);

            log.info("[AUDIT] {} {} id={} by session={} trace={}",
                    operation, entityType, entityId, sessionId, entry.getTraceId());
        } catch (Exception e) { // Resilience: audit logging is non-critical
            log.warn("[AUDIT] Failed to record audit log: {} {} id={} - {}",
                    operation, entityType, entityId, e.getMessage());
        }
    }

    /**
     * Query audit history for an entity.
     */
    public Page<RagAuditLog> getEntityHistory(String entityType, String entityId, int page, int size) {
        return repository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                entityType, entityId, PageRequest.of(page, size));
    }

    /**
     * Query audit records for a session.
     */
    public Page<RagAuditLog> getSessionHistory(String sessionId, int page, int size) {
        return repository.findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(page, size));
    }

    /**
     * Query recent audit records.
     */
    public List<RagAuditLog> getRecentAuditLogs(int limit) {
        return repository.findTop20ByOrderByCreatedAtDesc();
    }

    /**
     * Clean up audit logs older than the cutoff time.
     */
    public long cleanup(ZonedDateTime cutoff) {
        return repository.deleteByCreatedAtBefore(cutoff);
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
