package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ApiKeyProvisioningOperation 实体字段回环（Batch 370）：setter/
 * getter 一致性与初始 null 状态。
 */
class ApiKeyProvisioningOperationTest {

    @Test
    void settersAndReadersRoundTripAllFields() {
        ApiKeyProvisioningOperation operation = new ApiKeyProvisioningOperation();

        assertEquals(null, operation.getId());
        operation.setId(9L);
        assertEquals(9L, operation.getId());

        operation.setOwnerId("owner-1");
        assertEquals("owner-1", operation.getOwnerId());

        operation.setIdempotencyKeyHash(
                "a".repeat(64));
        assertEquals("a".repeat(64), operation.getIdempotencyKeyHash());

        operation.setRequestFingerprintSha256("b".repeat(64));
        assertEquals("b".repeat(64),
                operation.getRequestFingerprintSha256());

        operation.setPrincipalId("db:7");
        assertEquals("db:7", operation.getPrincipalId());

        operation.setCredentialId("cred-1");
        assertEquals("cred-1", operation.getCredentialId());

        assertEquals(null, operation.getCredentialVersion());
        operation.setCredentialVersion(3);
        assertEquals(3, operation.getCredentialVersion());

        LocalDateTime created = LocalDateTime.of(2026, 9, 14, 10, 0);
        LocalDateTime updated = LocalDateTime.of(2026, 9, 14, 10, 1);
        LocalDateTime completed = LocalDateTime.of(2026, 9, 14, 10, 2);
        operation.setCreatedAt(created);
        operation.setUpdatedAt(updated);
        operation.setCompletedAt(completed);
        assertEquals(created, operation.getCreatedAt());
        assertEquals(updated, operation.getUpdatedAt());
        assertEquals(completed, operation.getCompletedAt());
    }
}
