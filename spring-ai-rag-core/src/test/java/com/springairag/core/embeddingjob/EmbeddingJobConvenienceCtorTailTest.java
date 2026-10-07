package com.springairag.core.embeddingjob;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * EmbeddingJob 便捷构造器长尾（Batch 966，JaCoCo 驱动）：21 参构造器
 * 对 24 参规范构造器的委托——requestGeneration 固定 0、documentKind 与
 * chunkerVersion 置 null。
 */
class EmbeddingJobConvenienceCtorTailTest {

    @Test
    void twentyOneArgConstructorDelegatesWithRetryDefaults() {
        UUID id = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-08-27T14:00:00Z");

        EmbeddingJob job = new EmbeddingJob(
                id,
                batchId,
                42L,
                7L,
                false,
                "hash-a",
                3L,
                EmbeddingJobStatus.QUEUED,
                1,
                4,
                now,
                "worker-1",
                now.plusSeconds(60),
                null,
                "stale lease",
                now.minusSeconds(30),
                now.minusSeconds(10),
                null,
                null,
                "api",
                "principal-9");

        // 透传字段原样落位。
        assertEquals(id, job.id());
        assertEquals(batchId, job.batchId());
        assertEquals(42L, job.documentId());
        assertEquals(7L, job.embeddingProfileId());
        assertFalse(job.force());
        assertEquals("hash-a", job.contentHash());
        assertEquals(3L, job.documentVersion());
        assertEquals(EmbeddingJobStatus.QUEUED, job.status());
        assertEquals(1, job.attemptCount());
        assertEquals(4, job.maxAttempts());
        assertEquals("worker-1", job.leaseOwner());
        assertEquals("stale lease", job.lastError());
        assertEquals("api", job.origin());
        assertEquals("principal-9", job.requestedByPrincipalId());

        // 委托补齐的三个重试演进字段取默认值。
        assertEquals(0L, job.requestGeneration());
        assertNull(job.documentKind());
        assertNull(job.chunkerVersion());
    }
}
