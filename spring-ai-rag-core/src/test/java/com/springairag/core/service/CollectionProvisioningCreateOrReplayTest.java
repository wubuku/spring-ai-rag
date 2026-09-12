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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createOrReplay 守卫与收敛矩阵（Batch 313）：开关/账本/参数守卫、
 * 数据访问异常映射为不可用、嵌套数据访问的运行时异常识别、竞态
 * 耗尽后台账回收（重放/复现 DUPLICATE/无法收敛）、指纹冲突重抛、
 * 成功建集合落台账。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CollectionProvisioningCreateOrReplayTest {

    private static final String OWNER = "db:principal-1";
    private static final String KEY_HASH = "a".repeat(64);

    @Mock CollectionProvisioningOperationRepository operationRepository;
    @Mock RagCollectionRepository collectionRepository;
    @Mock RagDocumentRepository documentRepository;
    @Mock RagCollectionService collectionService;

    private RagProperties properties;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
    }

    private CollectionProvisioningService service(boolean withLedger) {
        return new CollectionProvisioningService(
                withLedger ? operationRepository : null,
                withLedger ? collectionRepository : null,
                withLedger ? documentRepository : null,
                withLedger ? collectionService : null,
                properties,
                transactionManager);
    }

    private CollectionRequest request() {
        CollectionRequest request = new CollectionRequest();
        request.setCollectionKey("kb");
        request.setName("Knowledge Base");
        return request;
    }

    private RagCollection collection() {
        RagCollection collection = new RagCollection();
        collection.setId(20L);
        collection.setCollectionKey("kb");
        collection.setName("Knowledge Base");
        return collection;
    }

    // ── 前置守卫 ────────────────────────────────────────────────────

    @Test
    void disabledIdempotencyRejected() {
        properties.getCollectionProvisioning().setEnabled(false);

        RagException error = assertThrows(RagException.class,
                () -> service(true).createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(
                ErrorCode.COLLECTION_PROVISIONING_IDEMPOTENCY_DISABLED,
                error.getErrorCodeEnum());
    }

    @Test
    void unavailableLedgerRejected() {
        RagException error = assertThrows(RagException.class,
                () -> service(false).createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("ledger is unavailable"));
    }

    @Test
    void ownerAndHashRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> service(true).createOrReplay(request(), null, KEY_HASH));
        assertThrows(IllegalArgumentException.class,
                () -> service(true).createOrReplay(request(), "  ", KEY_HASH));
        assertThrows(IllegalArgumentException.class,
                () -> service(true).createOrReplay(request(), OWNER, null));
        assertThrows(NullPointerException.class,
                () -> service(true).createOrReplay(null, OWNER, KEY_HASH));
    }

    // ── 异常分类 ────────────────────────────────────────────────────

    @Test
    void dataAccessFailureMapsToUnavailable() {
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        RagException error = assertThrows(RagException.class,
                () -> service(true).createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("ledger is unavailable"));
    }

    @Test
    void runtimeWithNestedDataAccessMapsToUnavailable() {
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenThrow(new IllegalStateException("wrapped",
                        new DataAccessResourceFailureException("root")));

        RagException error = assertThrows(RagException.class,
                () -> service(true).createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
    }

    @Test
    void plainRuntimeFailureRethrownAsIs() {
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenThrow(new IllegalStateException("logic"));

        assertThrows(IllegalStateException.class,
                () -> service(true).createOrReplay(request(), OWNER, KEY_HASH));
    }

    // ── 竞态收敛 ────────────────────────────────────────────────────

    @Test
    void raceRecoversViaLedgerReplayAfterRetryExhaustion() {
        properties.getCollectionProvisioning().setConcurrentRetryAttempts(1);
        RagCollection existing = collection();
        CollectionProvisioningOperation operation =
                new CollectionProvisioningOperation();
        operation.setRequestFingerprintSha256(
                CollectionProvisioningFingerprint.sha256(request()));
        operation.setCollectionId(20L);
        // 第一次：唯一约束冲突；第二次（台账回收）：命中既有操作。
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenThrow(new DataIntegrityViolationException("race"))
                .thenReturn(Optional.of(operation));
        when(collectionRepository.findById(20L))
                .thenReturn(Optional.of(existing));
        when(documentRepository.countByCollectionId(20L)).thenReturn(3L);

        CollectionProvisioningService.ProvisioningResult result =
                service(true).createOrReplay(request(), OWNER, KEY_HASH);

        assertTrue(result.replay());
        assertEquals(20L, result.collection().getId());
        assertEquals(3L, result.documentCount());
    }

    @Test
    void duplicateRethrownWhenRaceCannotBeResolved() {
        properties.getCollectionProvisioning().setConcurrentRetryAttempts(1);
        RagException duplicate = new RagException(
                ErrorCode.DUPLICATE_RESOURCE, "exists");
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenThrow(duplicate)
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service(true).createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(ErrorCode.DUPLICATE_RESOURCE, error.getErrorCodeEnum());
    }

    @Test
    void unresolvedRaceWithoutDuplicateReportsUnableToResolve() {
        properties.getCollectionProvisioning().setConcurrentRetryAttempts(1);
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenThrow(new DataIntegrityViolationException("race"))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service(true).createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
        assertTrue(error.getMessage()
                .contains("Unable to resolve a concurrent Collection"));
    }

    @Test
    void ledgerFailureDuringRecoveryMapsToUnavailable() {
        properties.getCollectionProvisioning().setConcurrentRetryAttempts(1);
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenThrow(new DataIntegrityViolationException("race"))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        RagException error = assertThrows(RagException.class,
                () -> service(true).createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
    }

    // ── 成功创建与指纹冲突 ──────────────────────────────────────────

    @Test
    void createPersistsOperationAndReturnsResult() {
        // 真实事务模板经 mocked PlatformTransactionManager 执行回调。
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenReturn(Optional.empty());
        when(collectionService.createCollection(any(CollectionRequest.class)))
                .thenReturn(collection());
        when(documentRepository.countByCollectionId(20L)).thenReturn(7L);

        CollectionProvisioningService.ProvisioningResult result =
                service(true).createOrReplay(request(), OWNER, KEY_HASH);

        assertFalse(result.replay());
        assertEquals(20L, result.collection().getId());
        assertEquals(7L, result.documentCount());
        verify(operationRepository).saveAndFlush(any(
                CollectionProvisioningOperation.class));
    }

    @Test
    void fingerprintConflictFromReplayRethrown() {
        CollectionProvisioningOperation conflicting =
                new CollectionProvisioningOperation();
        conflicting.setRequestFingerprintSha256("different");
        conflicting.setCollectionId(20L);
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenReturn(Optional.of(conflicting));

        RagException error = assertThrows(RagException.class,
                () -> service(true).createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                error.getErrorCodeEnum());
    }
}
