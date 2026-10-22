package com.springairag.core.service;

import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagCollectionPurgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/** 使用持久化 CAS 租约协调多实例 Collection Purge 分批执行。 */
@Component
@Profile("postgresql")
public class CollectionPurgeOperationWorker {

    private static final Logger log = LoggerFactory.getLogger(CollectionPurgeOperationWorker.class);

    private final JdbcTemplate jdbcTemplate;
    private final CollectionPurgeService purgeService;
    private final RagCollectionPurgeProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final String workerId = UUID.randomUUID().toString();

    public CollectionPurgeOperationWorker(
            JdbcTemplate jdbcTemplate,
            CollectionPurgeService purgeService,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.purgeService = purgeService;
        this.properties = ragProperties.getCollectionPurge();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${rag.long-running-operations.poll-interval-ms:1000}")
    public void processOneOperation() {
        Claim claim;
        try {
            claim = transactionTemplate.execute(status -> claim());
        } catch (NoWorkerSlotAvailableException unavailable) {
            return;
        }
        if (claim == null) {
            return;
        }
        try {
            purgeService.processAsyncWorkerBatch(claim);
        } catch (RuntimeException exception) {
            log.warn("Collection Purge coordinator failed: operationId={}, type={}",
                    claim.operationId(), exception.getClass().getSimpleName());
            try {
                transactionTemplate.executeWithoutResult(status ->
                        purgeService.recordAsyncWorkerFailure(claim));
            } catch (RuntimeException recoveryFailure) {
                log.error("Collection Purge coordinator recovery failed: operationId={}, type={}",
                        claim.operationId(), recoveryFailure.getClass().getSimpleName());
            }
        }
    }

    private Claim claim() {
        UUID token = UUID.randomUUID();
        List<Claim> claimed = jdbcTemplate.query("""
                UPDATE rag_long_running_operations operation
                SET status = 'RUNNING', lease_owner = ?, lease_token = ?,
                    lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                    heartbeat_at = clock_timestamp(), updated_at = clock_timestamp(),
                    next_retry_at = NULL
                WHERE operation.id = (
                    SELECT candidate.id
                    FROM rag_long_running_operations candidate
                    WHERE candidate.operation_kind = 'COLLECTION_PURGE'
                      AND candidate.status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                      AND (candidate.status <> 'RETRY_WAIT'
                           OR candidate.next_retry_at <= clock_timestamp())
                      AND (candidate.lease_token IS NULL
                           OR candidate.lease_expires_at <= clock_timestamp())
                    ORDER BY candidate.created_at, candidate.id
                    LIMIT 1
                )
                  AND operation.status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                  AND (operation.status <> 'RETRY_WAIT'
                       OR operation.next_retry_at <= clock_timestamp())
                  AND (operation.lease_token IS NULL
                       OR operation.lease_expires_at <= clock_timestamp())
                RETURNING operation.id, operation.lease_token
                """, (rs, rowNum) -> new Claim(
                rs.getObject("id", UUID.class), rs.getObject("lease_token", UUID.class), -1),
                workerId, token, properties.getAsyncOperationLease().toMillis());
        if (claimed.isEmpty()) {
            return null;
        }
        Claim operation = claimed.getFirst();
        List<Integer> slots = jdbcTemplate.query("""
                UPDATE rag_collection_purge_worker_slots slot
                SET operation_id = ?, lease_owner = ?, lease_token = ?,
                    lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE slot.slot_id = (
                    SELECT candidate.slot_id
                    FROM rag_collection_purge_worker_slots candidate
                    WHERE candidate.slot_id <= ?
                      AND (candidate.operation_id IS NULL
                           OR candidate.operation_id = ?)
                      AND (candidate.lease_token IS NULL
                           OR candidate.lease_expires_at <= clock_timestamp())
                    ORDER BY candidate.slot_id
                    LIMIT 1
                )
                  AND (slot.operation_id IS NULL OR slot.operation_id = ?)
                  AND (slot.lease_token IS NULL
                       OR slot.lease_expires_at <= clock_timestamp())
                RETURNING slot.slot_id
                """, (rs, rowNum) -> rs.getInt(1),
                operation.operationId(), workerId, operation.leaseToken(),
                properties.getAsyncOperationLease().toMillis(),
                properties.getAsyncWorkerConcurrency(), operation.operationId(),
                operation.operationId());
        if (slots.isEmpty()) {
            throw new NoWorkerSlotAvailableException();
        }
        return new Claim(operation.operationId(), operation.leaseToken(), slots.getFirst());
    }

    record Claim(UUID operationId, UUID leaseToken, int slotId) {
    }

    private static final class NoWorkerSlotAvailableException extends DataAccessException {
        private NoWorkerSlotAvailableException() {
            super("No Collection Purge worker slot is available");
        }
    }
}
