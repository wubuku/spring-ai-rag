package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 实体类访问器全字段往返（Batch 689，JaCoCo 驱动）：RagDocument
 * 的 nextHistoryVersion / lastSeenSyncRunId / lastSeenSyncGeneration
 * 访问器、CollectionProvisioningOperation 的 id/ownerId/idempotency
 * KeyHash/fingerprint/collectionId/createdAt/updatedAt 存取。
 */
class EntityAccessorRoundTripTest {

    @Test
    void ragDocumentNextHistoryVersionAndSyncRunAccessors() {
        RagDocument doc = new RagDocument();

        assertEquals(1, doc.getNextHistoryVersion());
        doc.setNextHistoryVersion(42);
        assertEquals(42, doc.getNextHistoryVersion());

        assertNull(doc.getLastSeenSyncRunId());
        var syncRunId = UUID.randomUUID();
        doc.setLastSeenSyncRunId(syncRunId);
        assertEquals(syncRunId, doc.getLastSeenSyncRunId());

        assertNull(doc.getLastSeenSyncGeneration());
        doc.setLastSeenSyncGeneration(7L);
        assertEquals(7L, doc.getLastSeenSyncGeneration());
    }

    @Test
    void collectionProvisioningOperationAccessors() {
        var operation = new CollectionProvisioningOperation();

        assertNull(operation.getId());
        operation.setId(1L);
        assertEquals(1L, operation.getId());

        assertNull(operation.getOwnerId());
        operation.setOwnerId("db:principal-1");
        assertEquals("db:principal-1", operation.getOwnerId());

        assertNull(operation.getIdempotencyKeyHash());
        operation.setIdempotencyKeyHash("hash-1");
        assertEquals("hash-1", operation.getIdempotencyKeyHash());

        assertNull(operation.getRequestFingerprintSha256());
        operation.setRequestFingerprintSha256("fp-1");
        assertEquals("fp-1", operation.getRequestFingerprintSha256());

        assertNull(operation.getCollectionId());
        operation.setCollectionId(20L);
        assertEquals(20L, operation.getCollectionId());

        var now = LocalDateTime.now();
        assertNull(operation.getCreatedAt());
        operation.setCreatedAt(now);
        assertEquals(now, operation.getCreatedAt());

        assertNull(operation.getUpdatedAt());
        operation.setUpdatedAt(now);
        assertEquals(now, operation.getUpdatedAt());
    }
}
