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
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 并发置备竞态语义：DUPLICATE_RESOURCE / 唯一约束冲突按竞态重试并
 * 从台账恢复、重试耗尽后抛出原始冲突、已清理（retired）集合拒绝
 * 重放、台账清理任务有界批量删除。
 */
@ExtendWith(MockitoExtension.class)
class CollectionProvisioningRaceTest {

    private static final String OWNER = "db:principal-1";
    private static final String KEY_HASH = "b".repeat(64);

    @Mock CollectionProvisioningOperationRepository operationRepository;
    @Mock RagCollectionRepository collectionRepository;
    @Mock RagDocumentRepository documentRepository;
    @Mock RagCollectionService collectionService;

    private RagProperties properties;
    private CollectionProvisioningService service;
    private CollectionRequest request;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        service = new CollectionProvisioningService(
                operationRepository,
                collectionRepository,
                documentRepository,
                collectionService,
                properties,
                null);
        request = request("tenant:manual:v1", "Manual");
    }

    private CollectionRequest request(String key, String name) {
        CollectionRequest value = new CollectionRequest();
        value.setCollectionKey(key);
        value.setName(name);
        return value;
    }

    private CollectionProvisioningOperation operationFor(RagCollection collection) {
        CollectionProvisioningOperation operation = new CollectionProvisioningOperation();
        operation.setOwnerId(OWNER);
        operation.setIdempotencyKeyHash(KEY_HASH);
        operation.setRequestFingerprintSha256(
                CollectionProvisioningFingerprint.sha256(request));
        operation.setCollectionId(collection.getId());
        operation.setCreatedAt(LocalDateTime.now());
        operation.setUpdatedAt(LocalDateTime.now());
        operation.setCompletedAt(LocalDateTime.now());
        return operation;
    }

    private RagCollection collection(long id) {
        RagCollection value = new RagCollection();
        value.setId(id);
        value.setCollectionKey("tenant:manual:v1");
        value.setName("Manual");
        return value;
    }

    private void stubExistingLookup(Optional<CollectionProvisioningOperation> value) {
        lenient().when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH)).thenReturn(value);
    }

    @Test
    void duplicateResourceRaceRecoversByReplayingExistingOperation() {
        RagCollection created = collection(7L);
        CollectionProvisioningOperation concurrent = operationFor(created);
        // 第一次查找为空（竞态开始），第二次能看到并发方写入的台账行。
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrent));
        when(collectionService.createCollection(request))
                .thenThrow(new RagException(
                        ErrorCode.DUPLICATE_RESOURCE, "duplicate key"));
        when(collectionRepository.findById(7L)).thenReturn(Optional.of(created));
        when(documentRepository.countByCollectionId(7L)).thenReturn(2L);

        CollectionProvisioningService.ProvisioningResult result =
                service.createOrReplay(request, OWNER, KEY_HASH);

        // 第一次尝试撞上并发创建的重复键，第二次尝试经台账重放恢复。
        assertEquals(7L, result.collection().getId());
        assertTrue(result.replay());
        assertEquals(2L, result.documentCount());
        verify(operationRepository, never()).saveAndFlush(any());
    }

    @Test
    void dataIntegrityRaceRecoversOnSecondAttempt() {
        RagCollection created = collection(8L);
        when(collectionService.createCollection(request))
                .thenThrow(new DataIntegrityViolationException("unique violation"))
                .thenReturn(created);
        when(operationRepository.saveAndFlush(any())).thenAnswer(
                invocation -> invocation.getArgument(0));
        when(documentRepository.countByCollectionId(8L)).thenReturn(0L);

        CollectionProvisioningService.ProvisioningResult result =
                service.createOrReplay(request, OWNER, KEY_HASH);

        assertFalse(result.replay());
        assertEquals(8L, result.collection().getId());
    }

    @Test
    void duplicateRaceExhaustsRetriesAndReThrowsOriginalConflict() {
        when(collectionService.createCollection(request))
                .thenThrow(new RagException(
                        ErrorCode.DUPLICATE_RESOURCE, "duplicate key"));
        stubExistingLookup(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.createOrReplay(request, OWNER, KEY_HASH));

        assertEquals(ErrorCode.DUPLICATE_RESOURCE, error.getErrorCodeEnum());
        assertEquals(3, properties.getCollectionProvisioning()
                .getConcurrentRetryAttempts());
        verify(collectionService, org.mockito.Mockito.times(3))
                .createCollection(request);
    }

    @Test
    void replayRejectsRetiredCollection() {
        RagCollection retired = collection(9L);
        retired.setPurgedAt(LocalDateTime.now());
        CollectionProvisioningOperation operation = operationFor(retired);
        stubExistingLookup(Optional.of(operation));
        when(collectionRepository.findById(9L)).thenReturn(Optional.of(retired));

        RagException error = assertThrows(RagException.class,
                () -> service.createOrReplay(request, OWNER, KEY_HASH));

        assertEquals(ErrorCode.COLLECTION_ALREADY_RETIRED,
                error.getErrorCodeEnum());
    }

    @Test
    void cleanupProvisioningLedgerDeletesCompletedRowsWhenEnabled() {
        when(operationRepository.deleteCompletedBefore(any(), eq(500)))
                .thenReturn(3);

        service.cleanupProvisioningLedger();

        verify(operationRepository).deleteCompletedBefore(any(), eq(500));
    }

    @Test
    void cleanupProvisioningLedgerSkipsWhenDisabled() {
        properties.getCollectionProvisioning().setEnabled(false);

        service.cleanupProvisioningLedger();

        verify(operationRepository, never()).deleteCompletedBefore(
                any(), anyInt());
    }
}
