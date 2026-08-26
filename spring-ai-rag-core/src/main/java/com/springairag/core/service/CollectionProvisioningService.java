package com.springairag.core.service;

import com.springairag.api.dto.CollectionRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagCollectionProvisioningProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.CollectionProvisioningOperation;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.CollectionProvisioningOperationRepository;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Collection 创建的持久化幂等协调器。
 */
@Service
public class CollectionProvisioningService {

    private static final Logger log =
            LoggerFactory.getLogger(CollectionProvisioningService.class);
    private static final String METRIC_NAME = "rag.collection.provisioning.requests";

    private final CollectionProvisioningOperationRepository operationRepository;
    private final RagCollectionRepository collectionRepository;
    private final RagDocumentRepository documentRepository;
    private final RagCollectionService collectionService;
    private final RagCollectionProvisioningProperties properties;
    private final TransactionTemplate transaction;
    private final MeterRegistry meterRegistry;

    @org.springframework.beans.factory.annotation.Autowired
    public CollectionProvisioningService(
            CollectionProvisioningOperationRepository operationRepository,
            RagCollectionRepository collectionRepository,
            RagDocumentRepository documentRepository,
            RagCollectionService collectionService,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager,
            ObjectProvider<MeterRegistry> meterRegistries) {
        this(operationRepository, collectionRepository, documentRepository,
                collectionService, ragProperties, transactionManager,
                meterRegistries.getIfAvailable());
    }

    public CollectionProvisioningService(
            CollectionProvisioningOperationRepository operationRepository,
            RagCollectionRepository collectionRepository,
            RagDocumentRepository documentRepository,
            RagCollectionService collectionService,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager) {
        this(operationRepository, collectionRepository, documentRepository,
                collectionService, ragProperties, transactionManager,
                (MeterRegistry) null);
    }

    private CollectionProvisioningService(
            CollectionProvisioningOperationRepository operationRepository,
            RagCollectionRepository collectionRepository,
            RagDocumentRepository documentRepository,
            RagCollectionService collectionService,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.operationRepository = operationRepository;
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.collectionService = collectionService;
        this.properties = Objects.requireNonNull(
                ragProperties, "ragProperties must not be null")
                .getCollectionProvisioning();
        this.transaction = transactionManager == null
                ? null
                : requiresNew(transactionManager);
        this.meterRegistry = meterRegistry;
    }

    public ProvisioningResult createOrReplay(
            CollectionRequest request,
            String ownerId,
            String idempotencyKeyHash) {
        if (!properties.isEnabled()) {
            record("disabled");
            throw new RagException(
                    ErrorCode.COLLECTION_PROVISIONING_IDEMPOTENCY_DISABLED,
                    "Collection provisioning idempotency is disabled");
        }
        if (operationRepository == null || collectionRepository == null
                || documentRepository == null || collectionService == null) {
            record("unavailable");
            throw unavailable("Collection provisioning ledger is unavailable", null);
        }
        Objects.requireNonNull(request, "request must not be null");
        if (ownerId == null || ownerId.isBlank()
                || idempotencyKeyHash == null || idempotencyKeyHash.isBlank()) {
            throw new IllegalArgumentException(
                    "Provisioning owner and idempotency hash are required");
        }

        String fingerprint = CollectionProvisioningFingerprint.sha256(request);
        RagException duplicate = null;
        RuntimeException lastRace = null;
        boolean raced = false;
        int attempts = properties.getConcurrentRetryAttempts();

        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                ProvisioningResult result = runTransaction(
                        request, ownerId, idempotencyKeyHash, fingerprint);
                record(result.replay() ? "replay" : "created");
                if (raced) {
                    record("race_recovered");
                }
                return result;
            } catch (RagException error) {
                if (error.getErrorCodeEnum() == ErrorCode.DUPLICATE_RESOURCE) {
                    duplicate = error;
                    lastRace = error;
                    raced = true;
                } else {
                    if (error.getErrorCodeEnum() == ErrorCode.IDEMPOTENCY_KEY_REUSED) {
                        record("fingerprint_conflict");
                    }
                    throw error;
                }
            } catch (DataIntegrityViolationException error) {
                lastRace = error;
                raced = true;
            } catch (DataAccessException error) {
                record("unavailable");
                throw unavailable("Collection provisioning ledger is unavailable", error);
            } catch (RuntimeException error) {
                if (containsDataAccess(error)) {
                    record("unavailable");
                    throw unavailable(
                            "Collection provisioning ledger is unavailable", error);
                }
                throw error;
            }
            if (attempt + 1 < attempts) {
                backoff(attempt);
            }
        }

        try {
            ProvisioningResult resolved = readExisting(
                    ownerId, idempotencyKeyHash, fingerprint);
            if (resolved != null) {
                record("race_recovered");
                record(resolved.replay() ? "replay" : "created");
                return resolved;
            }
        } catch (DataAccessException error) {
            record("unavailable");
            throw unavailable("Collection provisioning ledger is unavailable", error);
        } catch (RuntimeException error) {
            if (containsDataAccess(error)) {
                record("unavailable");
                throw unavailable("Collection provisioning ledger is unavailable", error);
            }
            throw error;
        }

        if (duplicate != null) {
            throw duplicate;
        }
        record("unavailable");
        throw unavailable(
                "Unable to resolve a concurrent Collection provisioning request",
                lastRace);
    }

    private ProvisioningResult runTransaction(
            CollectionRequest request,
            String ownerId,
            String idempotencyKeyHash,
            String fingerprint) {
        if (transaction == null) {
            return provisionInCurrentTransaction(
                    request, ownerId, idempotencyKeyHash, fingerprint);
        }
        return transaction.execute(status -> provisionInCurrentTransaction(
                request, ownerId, idempotencyKeyHash, fingerprint));
    }

    private ProvisioningResult provisionInCurrentTransaction(
            CollectionRequest request,
            String ownerId,
            String idempotencyKeyHash,
            String fingerprint) {
        Optional<CollectionProvisioningOperation> existing =
                operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                        ownerId, idempotencyKeyHash);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        RagCollection collection = collectionService.createCollection(request);
        LocalDateTime now = LocalDateTime.now();
        CollectionProvisioningOperation operation =
                new CollectionProvisioningOperation();
        operation.setOwnerId(ownerId);
        operation.setIdempotencyKeyHash(idempotencyKeyHash);
        operation.setRequestFingerprintSha256(fingerprint);
        operation.setCollectionId(collection.getId());
        operation.setCreatedAt(now);
        operation.setUpdatedAt(now);
        operation.setCompletedAt(now);
        operationRepository.saveAndFlush(operation);
        return result(collection, false);
    }

    private ProvisioningResult readExisting(
            String ownerId,
            String idempotencyKeyHash,
            String fingerprint) {
        if (transaction == null) {
            return operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                            ownerId, idempotencyKeyHash)
                    .map(operation -> replay(operation, fingerprint))
                    .orElse(null);
        }
        return transaction.execute(status ->
                operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                                ownerId, idempotencyKeyHash)
                        .map(operation -> replay(operation, fingerprint))
                        .orElse(null));
    }

    private ProvisioningResult replay(
            CollectionProvisioningOperation operation,
            String fingerprint) {
        if (!fingerprint.equals(operation.getRequestFingerprintSha256())) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "The Idempotency-Key was already used for a different request");
        }
        RagCollection collection = collectionRepository
                .findById(operation.getCollectionId())
                .orElseThrow(() -> unavailable(
                        "The Collection provisioning result is unavailable", null));
        return result(collection, true);
    }

    private ProvisioningResult result(RagCollection collection, boolean replay) {
        long documentCount = documentRepository.countByCollectionId(collection.getId());
        return new ProvisioningResult(collection, documentCount, replay);
    }

    @Scheduled(
            fixedDelayString =
                    "${rag.collection-provisioning.cleanup-interval-ms:3600000}",
            zone = "${spring.task.scheduling.timezone:Asia/Shanghai}")
    public void cleanupProvisioningLedger() {
        if (!properties.isEnabled() || operationRepository == null) {
            return;
        }
        try {
            int deleted = operationRepository.deleteCompletedBefore(
                    LocalDateTime.now().minus(properties.getRetention()),
                    properties.getCleanupBatchSize());
            if (deleted > 0) {
                record("cleanup_deleted");
            }
        } catch (DataAccessException error) {
            record("cleanup_failed");
            log.warn("Collection provisioning ledger cleanup failed");
        }
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(25L * (attempt + 1));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            record("unavailable");
            throw unavailable("Collection provisioning retry was interrupted", error);
        }
    }

    private void record(String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_NAME, "outcome", outcome).increment();
        }
    }

    private boolean containsDataAccess(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DataAccessException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private RagException unavailable(String message, Throwable cause) {
        return cause == null
                ? new RagException(ErrorCode.SERVICE_UNAVAILABLE, message)
                : new RagException(ErrorCode.SERVICE_UNAVAILABLE, message, cause);
    }

    private static TransactionTemplate requiresNew(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    public record ProvisioningResult(
            RagCollection collection,
            long documentCount,
            boolean replay) {
    }
}
