package com.springairag.core.service;

import com.springairag.api.dto.CollectionRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.CollectionProvisioningOperation;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.CollectionProvisioningOperationRepository;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.CollectionProvisioningService.ProvisioningResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CollectionProvisioning 竞态收尾长尾（Batch 691，JaCoCo 驱动）：
 * 事务路径下重试耗尽后的台账竞争恢复、台账 DataAccess 包装异常
 * 的不可用降级、指纹冲突的原样重抛。
 */
class CollectionProvisioningRaceLedgerTailTest {

    private static final String OWNER = "db:principal-691";
    private static final String KEY_HASH = "b".repeat(64);

    private CollectionProvisioningOperationRepository operationRepository;
    private RagCollectionRepository collectionRepository;
    private RagDocumentRepository documentRepository;
    private RagCollectionService collectionService;
    private RagProperties properties;
    private CollectionProvisioningService service;

    @BeforeEach
    void setUp() {
        operationRepository = mock(CollectionProvisioningOperationRepository.class);
        collectionRepository = mock(RagCollectionRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        collectionService = mock(RagCollectionService.class);
        properties = new RagProperties();
        properties.getCollectionProvisioning().setConcurrentRetryAttempts(2);
        // 公共构造器注入事务管理器 → runTransaction / readExisting 走
        // TransactionTemplate 路径（mock 的 PlatformTransactionManager
        // 返回 null status，回调照常执行）。
        service = new CollectionProvisioningService(
                operationRepository,
                collectionRepository,
                documentRepository,
                collectionService,
                properties,
                mock(PlatformTransactionManager.class));
    }

    private CollectionRequest request() {
        CollectionRequest request = new CollectionRequest();
        request.setCollectionKey("tenant:race-691:v1");
        request.setName("Race 691");
        return request;
    }

    private RagCollection collection() {
        RagCollection collection = new RagCollection();
        collection.setId(9L);
        collection.setCollectionKey("tenant:race-691:v1");
        collection.setName("Race 691");
        collection.setDimensions(1024);
        collection.setEnabled(true);
        return collection;
    }

    private CollectionProvisioningOperation operation(String fingerprint) {
        CollectionProvisioningOperation operation =
                new CollectionProvisioningOperation();
        operation.setOwnerId(OWNER);
        operation.setIdempotencyKeyHash(KEY_HASH);
        operation.setRequestFingerprintSha256(fingerprint);
        operation.setCollectionId(9L);
        return operation;
    }

    private void stubRaceExhaustionInLoop() {
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH)).thenReturn(Optional.empty());
        when(collectionService.createCollection(any()))
                .thenThrow(new DataIntegrityViolationException("raced"))
                .thenThrow(new DataIntegrityViolationException("raced"));
    }

    @Test
    void raceExhaustionRecoversReplayFromTransactionLedger() {
        stubRaceExhaustionInLoop();
        String fingerprint =
                CollectionProvisioningFingerprint.sha256(request());
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(operation(fingerprint)));
        when(collectionRepository.findById(9L))
                .thenReturn(Optional.of(collection()));
        when(documentRepository.countByCollectionId(9L)).thenReturn(0L);

        ProvisioningResult result =
                service.createOrReplay(request(), OWNER, KEY_HASH);

        assertTrue(result.replay());
        assertEquals(9L, result.collection().getId());
    }

    @Test
    void raceExhaustionWithWrappedDataAccessYieldsUnavailable() {
        stubRaceExhaustionInLoop();
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenThrow(new IllegalStateException(
                        "wrapped",
                        new DataAccessResourceFailureException("db down")));

        RagException error = assertThrows(RagException.class,
                () -> service.createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("ledger is unavailable"));
    }

    @Test
    void raceExhaustionWithFingerprintConflictRethrowsAsIs() {
        stubRaceExhaustionInLoop();
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(operation("mismatched-fingerprint")));

        RagException error = assertThrows(RagException.class,
                () -> service.createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                error.getErrorCodeEnum());
    }
}
