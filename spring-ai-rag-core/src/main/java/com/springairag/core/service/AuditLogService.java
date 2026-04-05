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
 * 审计日志服务
 *
 * <p>为关键业务操作（创建/更新/删除）提供结构化审计日志记录。
 * 通过 {@link ConditionalOnBean} 条件注册，仅在 RagAuditLogRepository 可用时创建。
 *
 * <p>审计事件通过 MDC 注入 traceId，通过 HttpServletRequest 获取 clientIp。
 * 操作失败时不阻断业务流程（韧性模式）。
 */
@Service
@ConditionalOnBean(RagAuditLogRepository.class)
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    /** 操作类型：创建 */
    public static final String OP_CREATE = "CREATE";
    /** 操作类型：更新 */
    public static final String OP_UPDATE = "UPDATE";
    /** 操作类型：删除 */
    public static final String OP_DELETE = "DELETE";
    /** 操作类型：导入 */
    public static final String OP_IMPORT = "IMPORT";
    /** 操作类型：嵌入 */
    public static final String OP_EMBED = "EMBED";
    /** 操作类型：清除缓存 */
    public static final String OP_CACHE_CLEAR = "CACHE_CLEAR";
    /** 操作类型：触发告警 */
    public static final String OP_ALERT_FIRE = "ALERT_FIRE";
    /** 操作类型：解决告警 */
    public static final String OP_ALERT_RESOLVE = "ALERT_RESOLVE";
    /** 操作类型：静默告警 */
    public static final String OP_ALERT_SILENCE = "ALERT_SILENCE";

    /** 实体类型：集合 */
    public static final String ENTITY_COLLECTION = "Collection";
    /** 实体类型：文档 */
    public static final String ENTITY_DOCUMENT = "Document";
    /** 实体类型：会话历史 */
    public static final String ENTITY_CHAT_HISTORY = "ChatHistory";
    /** 实体类型：A/B 实验 */
    public static final String ENTITY_AB_TEST = "AbTest";
    /** 实体类型：告警 */
    public static final String ENTITY_ALERT = "Alert";
    /** 实体类型：SLO 配置 */
    public static final String ENTITY_SLO_CONFIG = "SloConfig";
    /** 实体类型：静默计划 */
    public static final String ENTITY_SILENCE_SCHEDULE = "SilenceSchedule";
    /** 实体类型：嵌入缓存 */
    public static final String ENTITY_EMBED_CACHE = "EmbedCache";

    private final RagAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Autowired
    public AuditLogService(RagAuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录创建操作
     */
    public void logCreate(String entityType, String entityId, String description) {
        logAudit(OP_CREATE, entityType, entityId, description, null, null);
    }

    /**
     * 记录创建操作（含额外属性）
     */
    public void logCreate(String entityType, String entityId, String description, Map<String, Object> details) {
        logAudit(OP_CREATE, entityType, entityId, description, details, null);
    }

    /**
     * 记录更新操作
     */
    public void logUpdate(String entityType, String entityId, String description) {
        logAudit(OP_UPDATE, entityType, entityId, description, null, null);
    }

    /**
     * 记录更新操作（含额外属性）
     */
    public void logUpdate(String entityType, String entityId, String description, Map<String, Object> details) {
        logAudit(OP_UPDATE, entityType, entityId, description, details, null);
    }

    /**
     * 记录删除操作
     */
    public void logDelete(String entityType, String entityId, String description) {
        logAudit(OP_DELETE, entityType, entityId, description, null, null);
    }

    /**
     * 记录删除操作（含额外属性）
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
            log.warn("[AUDIT] 记录审计日志失败: {} {} id={} - {}",
                    operation, entityType, entityId, e.getMessage());
        }
    }

    /**
     * 查询实体的审计历史
     */
    public Page<RagAuditLog> getEntityHistory(String entityType, String entityId, int page, int size) {
        return repository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                entityType, entityId, PageRequest.of(page, size));
    }

    /**
     * 查询会话的审计记录
     */
    public Page<RagAuditLog> getSessionHistory(String sessionId, int page, int size) {
        return repository.findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(page, size));
    }

    /**
     * 查询最近的审计记录
     */
    public List<RagAuditLog> getRecentAuditLogs(int limit) {
        return repository.findTop20ByOrderByCreatedAtDesc();
    }

    /**
     * 清理指定时间之前的审计日志
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
