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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createOrReplay 并发竞态回收：DUPLICATE_RESOURCE 与唯一约束冲突
 * 视为竞态并退避重试；重试耗尽后经台账 readExisting 收敛（重放或
 * 复现 DUPLICATE）；重放集合已清退 → COLLECTION_ALREADY_RETIRED。
 */
@ExtendWith(MockitoExtension.class)
class CollectionProvisioningRaceRecoveryTest {

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
        service = new CollectionProvisioningService(
                operationRepository,
                collectionRepository,
                documentRepository,
                collectionService,
                properties,
                null);
    }

    private CollectionRequest request(String key, String name) {
        CollectionRequest request = new CollectionRequest();
        request.setCollectionKey(key);
        request.setName(name);
        return request;
    }

    private RagCollection collection(Long id, String key, String name) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        collection.setName(name);
        collection.setDimensions(1024);
        collection.setEnabled(true);
        return collection;
    }

    private CollectionProvisioningOperation operation(
            CollectionRequest request, Long collectionId) {
        CollectionProvisioningOperation operation =
                new CollectionProvisioningOperation();
        operation.setOwnerId(OWNER);
        operation.setIdempotencyKeyHash(KEY_HASH);
        operation.setRequestFingerprintSha256(
                CollectionProvisioningFingerprint.sha256(request));
        operation.setCollectionId(collectionId);
        return operation;
    }

    @Test
    void duplicateRaceRecoversAndCreatesOnRetry() {
        RagCollection created = collection(7L, "tenant:manual:v1", "Manual");
        when(collectionService.createCollection(any()))
                .thenThrow(new RagException(
                        ErrorCode.DUPLICATE_RESOURCE, "raced"))
                .thenReturn(created);
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH)).thenReturn(Optional.empty());
        when(documentRepository.countByCollectionId(7L)).thenReturn(0L);

        CollectionProvisioningService.ProvisioningResult result =
                service.createOrReplay(
                        request("tenant:manual:v1", "Manual"),
                        OWNER, KEY_HASH);

        assertFalse(result.replay());
        assertEquals(7L, result.collection().getId());
        verify(collectionService, times(2)).createCollection(any());
    }

    @Test
    void uniqueConstraintViolationIsTreatedAsRaceAndRetried() {
        RagCollection created = collection(7L, "tenant:manual:v1", "Manual");
        when(collectionService.createCollection(any()))
                .thenThrow(new DataIntegrityViolationException("uniq duplicate key"))
                .thenReturn(created);
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH)).thenReturn(Optional.empty());
        when(documentRepository.countByCollectionId(7L)).thenReturn(0L);

        CollectionProvisioningService.ProvisioningResult result =
                service.createOrReplay(
                        request("tenant:manual:v1", "Manual"),
                        OWNER, KEY_HASH);

        assertFalse(result.replay());
        verify(collectionService, times(2)).createCollection(any());
    }

    @Test
    void exhaustedRetriesResolveViaLedgerReplay() {
        when(collectionService.createCollection(any()))
                .thenThrow(new RagException(
                        ErrorCode.DUPLICATE_RESOURCE, "raced"));
        CollectionRequest request = request("tenant:manual:v1", "Manual");
        // 循环 3 次尝试内台账为空，耗尽后 readExisting 第 4 次查询读到。
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(operation(request, 7L)));
        RagCollection persisted = collection(7L, "tenant:manual:v1", "Manual");
        when(collectionRepository.findById(7L))
                .thenReturn(Optional.of(persisted));
        when(documentRepository.countByCollectionId(7L)).thenReturn(2L);

        CollectionProvisioningService.ProvisioningResult result =
                service.createOrReplay(request, OWNER, KEY_HASH);

        // 三次尝试全部撞车后，从台账读回已建集合（重放语义）。
        assertTrue(result.replay());
        assertEquals(7L, result.collection().getId());
        verify(collectionService, times(3)).createCollection(any());
    }

    @Test
    void exhaustedRetriesRethrowDuplicateWhenLedgerEmpty() {
        when(collectionService.createCollection(any()))
                .thenThrow(new RagException(
                        ErrorCode.DUPLICATE_RESOURCE, "raced"));
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH)).thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.createOrReplay(
                        request("tenant:manual:v1", "Manual"), OWNER, KEY_HASH));

        assertEquals(ErrorCode.DUPLICATE_RESOURCE, error.getErrorCodeEnum());
    }

    @Test
    void purgedReplayCollectionRetiresPermanently() {
        CollectionRequest request = request("tenant:manual:v1", "Manual");
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenReturn(Optional.of(operation(request, 7L)));
        RagCollection purged = collection(7L, "tenant:manual:v1", "Manual");
        purged.setPurgedAt(LocalDateTime.now());
        when(collectionRepository.findById(7L))
                .thenReturn(Optional.of(purged));

        RagException error = assertThrows(RagException.class,
                () -> service.createOrReplay(request, OWNER, KEY_HASH));

        assertEquals(ErrorCode.COLLECTION_ALREADY_RETIRED,
                error.getErrorCodeEnum());
    }

    @Test
    void readExistingDataAccessFailureSurfacesServiceUnavailable() {
        when(collectionService.createCollection(any()))
                .thenThrow(new RagException(
                        ErrorCode.DUPLICATE_RESOURCE, "raced"));
        // 前 3 次尝试台账为空，耗尽后 readExisting 查询离线。
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.empty())
                .thenThrow(new org.springframework.dao.
                        DataAccessResourceFailureException("offline"));

        RagException error = assertThrows(RagException.class,
                () -> service.createOrReplay(
                        request("tenant:manual:v1", "Manual"), OWNER, KEY_HASH));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
    }
}
