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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollectionProvisioningServiceTest {

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

    @Test
    void createsThenReplaysCurrentCollectionStateAndCount() {
        CollectionRequest request = request("tenant:manual:v1", "Manual");
        RagCollection created = collection(7L, "tenant:manual:v1", "Manual", false);
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                "db:principal-1", "a".repeat(64)))
                .thenReturn(Optional.empty());
        when(collectionService.createCollection(request)).thenReturn(created);
        when(documentRepository.countByCollectionId(7L)).thenReturn(0L);

        CollectionProvisioningService.ProvisioningResult first =
                service.createOrReplay(
                        request, "db:principal-1", "a".repeat(64));

        assertFalse(first.replay());
        assertEquals(0L, first.documentCount());
        ArgumentCaptor<CollectionProvisioningOperation> operation =
                ArgumentCaptor.forClass(CollectionProvisioningOperation.class);
        verify(operationRepository).saveAndFlush(operation.capture());
        assertEquals(7L, operation.getValue().getCollectionId());

        RagCollection current =
                collection(7L, "tenant:manual:v1", "Renamed", true);
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                "db:principal-1", "a".repeat(64)))
                .thenReturn(Optional.of(operation.getValue()));
        when(collectionRepository.findById(7L)).thenReturn(Optional.of(current));
        when(documentRepository.countByCollectionId(7L)).thenReturn(4L);

        CollectionProvisioningService.ProvisioningResult replay =
                service.createOrReplay(
                        request, "db:principal-1", "a".repeat(64));

        assertTrue(replay.replay());
        assertEquals("Renamed", replay.collection().getName());
        assertTrue(replay.collection().getDeleted());
        assertEquals(4L, replay.documentCount());
        verify(collectionService).createCollection(request);
    }

    @Test
    void rejectsFingerprintConflictBeforeCreatingAnotherCollection() {
        CollectionRequest original = request("tenant:manual:v1", "Manual");
        CollectionProvisioningOperation operation =
                operation(original, 7L, "a".repeat(64));
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                "db:principal-1", "a".repeat(64)))
                .thenReturn(Optional.of(operation));

        CollectionRequest changed = request("tenant:manual:v2", "Different");
        RagException error = assertThrows(
                RagException.class,
                () -> service.createOrReplay(
                        changed, "db:principal-1", "a".repeat(64)));

        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, error.getErrorCodeEnum());
        verify(collectionService, never()).createCollection(any());
    }

    @Test
    void featureDisabledAndLedgerFailuresFailClosed() {
        properties.getCollectionProvisioning().setEnabled(false);
        RagException disabled = assertThrows(
                RagException.class,
                () -> service.createOrReplay(
                        request("tenant:a", "A"),
                        "local:auth-disabled", "a".repeat(64)));
        assertEquals(
                ErrorCode.COLLECTION_PROVISIONING_IDEMPOTENCY_DISABLED,
                disabled.getErrorCodeEnum());

        properties.getCollectionProvisioning().setEnabled(true);
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                "local:auth-disabled", "a".repeat(64)))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        RagException unavailable = assertThrows(
                RagException.class,
                () -> service.createOrReplay(
                        request("tenant:a", "A"),
                        "local:auth-disabled", "a".repeat(64)));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, unavailable.getErrorCodeEnum());
        verify(collectionService, never()).createCollection(any());
    }

    @Test
    void missingReplayCollectionFailsClosed() {
        CollectionRequest request = request("tenant:manual:v1", "Manual");
        CollectionProvisioningOperation operation =
                operation(request, 7L, "a".repeat(64));
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                "db:principal-1", "a".repeat(64)))
                .thenReturn(Optional.of(operation));
        when(collectionRepository.findById(7L)).thenReturn(Optional.empty());

        RagException error = assertThrows(
                RagException.class,
                () -> service.createOrReplay(
                        request, "db:principal-1", "a".repeat(64)));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
    }

    @Test
    void cleanupUsesRetentionAndDoesNotTouchCollections() {
        when(operationRepository.deleteCompletedBefore(any(), any(Integer.class)))
                .thenReturn(2);

        service.cleanupProvisioningLedger();

        verify(operationRepository).deleteCompletedBefore(any(LocalDateTime.class), any(Integer.class));
        verify(collectionRepository, never()).deleteAll();
    }

    private CollectionProvisioningOperation operation(
            CollectionRequest request, Long collectionId, String keyHash) {
        CollectionProvisioningOperation operation =
                new CollectionProvisioningOperation();
        operation.setOwnerId("db:principal-1");
        operation.setIdempotencyKeyHash(keyHash);
        operation.setRequestFingerprintSha256(
                CollectionProvisioningFingerprint.sha256(request));
        operation.setCollectionId(collectionId);
        return operation;
    }

    private CollectionRequest request(String key, String name) {
        CollectionRequest request = new CollectionRequest();
        request.setCollectionKey(key);
        request.setName(name);
        return request;
    }

    private RagCollection collection(
            Long id, String key, String name, boolean deleted) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        collection.setName(name);
        collection.setDimensions(1024);
        collection.setEnabled(true);
        collection.setDeleted(deleted);
        return collection;
    }
}
