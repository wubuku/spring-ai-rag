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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CollectionProvisioningService 长尾（Batch 521，JaCoCo 驱动）：
 * 幂等开关关闭与台账不可用拒绝、重试耗尽后经 readExisting 收敛为
 * 重放、退避被中断时透出不可用、cleanupProvisioningLedger 的删除
 * 计数/失败吞并与关闭短路。
 */
@ExtendWith(MockitoExtension.class)
class CollectionProvisioningTailTest {

    private static final String OWNER = "db:principal-1";
    private static final String KEY_HASH = "a".repeat(64);

    @Mock CollectionProvisioningOperationRepository operationRepository;
    @Mock RagCollectionRepository collectionRepository;
    @Mock RagDocumentRepository documentRepository;
    @Mock RagCollectionService collectionService;

    private RagProperties properties;
    private CollectionProvisioningService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getCollectionProvisioning().setConcurrentRetryAttempts(2);
        service = new CollectionProvisioningService(
                operationRepository,
                collectionRepository,
                documentRepository,
                collectionService,
                properties,
                null);
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    private CollectionRequest request() {
        CollectionRequest request = new CollectionRequest();
        request.setCollectionKey("tenant:manual:v1");
        request.setName("Manual");
        return request;
    }

    private RagCollection collection(Long id) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey("tenant:manual:v1");
        collection.setName("Manual");
        collection.setDimensions(1024);
        collection.setEnabled(true);
        return collection;
    }

    private CollectionProvisioningOperation matchingOperation() {
        CollectionProvisioningOperation operation =
                new CollectionProvisioningOperation();
        operation.setOwnerId(OWNER);
        operation.setIdempotencyKeyHash(KEY_HASH);
        operation.setRequestFingerprintSha256(
                CollectionProvisioningFingerprint.sha256(request()));
        operation.setCollectionId(9L);
        return operation;
    }

    @Test
    void disabledProvisioningIsRejectedBeforeLedgerAccess() {
        properties.getCollectionProvisioning().setEnabled(false);

        var error = assertThrows(RagException.class,
                () -> service.createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(
                ErrorCode.COLLECTION_PROVISIONING_IDEMPOTENCY_DISABLED,
                error.getErrorCodeEnum());
        verify(operationRepository, never())
                .findByOwnerIdAndIdempotencyKeyHash(any(), any());
    }

    @Test
    void missingLedgerDependenciesSurfacesUnavailable() {
        var broken = new CollectionProvisioningService(
                null, null, null, collectionService, properties, null);

        var error = assertThrows(RagException.class,
                () -> broken.createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("ledger is unavailable"));
    }

    @Test
    void retryExhaustionConvergesToExistingReplay() {
        when(collectionService.createCollection(any()))
                .thenThrow(new RagException(
                        ErrorCode.DUPLICATE_RESOURCE, "raced"))
                .thenThrow(new RagException(
                        ErrorCode.DUPLICATE_RESOURCE, "raced again"));
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(matchingOperation()));
        when(collectionRepository.findById(9L))
                .thenReturn(Optional.of(collection(9L)));
        when(documentRepository.countByCollectionId(9L)).thenReturn(0L);

        var result = service.createOrReplay(request(), OWNER, KEY_HASH);

        assertTrue(result.replay());
        assertEquals(9L, result.collection().getId());
    }

    @Test
    void interruptedBackoffSurfacesUnavailable() {
        Thread.currentThread().interrupt();
        when(collectionService.createCollection(any()))
                .thenThrow(new RagException(
                        ErrorCode.DUPLICATE_RESOURCE, "raced"));

        var error = assertThrows(RagException.class,
                () -> service.createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("retry was interrupted"));
        assertTrue(Thread.interrupted(), "中断标志应已被置位");
    }

    @Test
    void cleanupRecordsOutcomeWhenRowsDeleted() {
        when(operationRepository.deleteCompletedBefore(
                any(), anyInt())).thenReturn(3);

        service.cleanupProvisioningLedger();

        verify(operationRepository).deleteCompletedBefore(any(), eq(500));
    }

    @Test
    void cleanupSwallowsDataAccessFailure() {
        when(operationRepository.deleteCompletedBefore(
                any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        service.cleanupProvisioningLedger();

        verify(operationRepository).deleteCompletedBefore(any(), eq(500));
    }

    @Test
    void cleanupSkipsWhenDisabled() {
        properties.getCollectionProvisioning().setEnabled(false);

        service.cleanupProvisioningLedger();

        verify(operationRepository, never())
                .deleteCompletedBefore(any(), anyInt());
    }
}
