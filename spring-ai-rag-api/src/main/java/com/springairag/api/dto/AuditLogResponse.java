package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.ZonedDateTime;

/**
 * Content-free audit view for management callers.
 *
 * <p>The persisted details field is intentionally not exposed by this DTO.
 */
@Schema(description = "Redacted audit log record without operation details or request content")
public record AuditLogResponse(
        Long id,
        String operation,
        String entityType,
        String entityId,
        String sessionId,
        String operator,
        String description,
        String clientIp,
        String traceId,
        ZonedDateTime createdAt) {
}
