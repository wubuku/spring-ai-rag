package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 轮换操作账本实体的列映射访问器：字段名与 Flyway 表结构一一对应，
 * 误改列名或漏配访问器会导致 JPA 装配失败。
 */
class ApiKeyRotationOperationTest {

    @Test
    void roundTripsEveryMappedColumn() {
        ApiKeyRotationOperation operation = new ApiKeyRotationOperation();
        UUID rotationId = UUID.randomUUID();
        LocalDateTime created = LocalDateTime.of(2026, 9, 8, 8, 0);
        LocalDateTime updated = created.plusMinutes(1);
        LocalDateTime terminal = created.plusHours(1);

        operation.setRotationId(rotationId);
        operation.setPrincipalId("rag_p_1");
        operation.setIdempotencyKeyHash("a".repeat(64));
        operation.setRequestFingerprintSha256("b".repeat(64));
        operation.setSourceCredentialId("rag_k_src_v1");
        operation.setTargetCredentialId("rag_k_dst_v2");
        operation.setOverlapSeconds(900);
        operation.setExpiresAt(created.plusHours(24));
        operation.setStatus(ApiKeyRotationStatus.PENDING);
        operation.setCreatedAt(created);
        operation.setUpdatedAt(updated);
        operation.setTerminalAt(terminal);

        assertEquals(rotationId, operation.getRotationId());
        assertEquals("rag_p_1", operation.getPrincipalId());
        assertEquals("a".repeat(64), operation.getIdempotencyKeyHash());
        assertEquals("b".repeat(64), operation.getRequestFingerprintSha256());
        assertEquals("rag_k_src_v1", operation.getSourceCredentialId());
        assertEquals("rag_k_dst_v2", operation.getTargetCredentialId());
        assertEquals(900, operation.getOverlapSeconds());
        assertEquals(created.plusHours(24), operation.getExpiresAt());
        assertEquals(ApiKeyRotationStatus.PENDING, operation.getStatus());
        assertEquals(created, operation.getCreatedAt());
        assertEquals(updated, operation.getUpdatedAt());
        assertEquals(terminal, operation.getTerminalAt());
    }

    @Test
    void terminalAtStartsAsNullForInFlightRotations() {
        ApiKeyRotationOperation operation = new ApiKeyRotationOperation();

        assertNull(operation.getTerminalAt());
        assertNull(operation.getStatus());
    }
}
