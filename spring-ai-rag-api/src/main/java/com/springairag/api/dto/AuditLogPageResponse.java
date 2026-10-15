package com.springairag.api.dto;

import java.util.List;

/** Stable pagination envelope for the management audit view. */
public record AuditLogPageResponse(
        List<AuditLogResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
