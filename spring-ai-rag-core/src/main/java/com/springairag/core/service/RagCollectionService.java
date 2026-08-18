package com.springairag.core.service;

import com.springairag.api.dto.CollectionCloneResponse;
import com.springairag.api.dto.CollectionRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.api.validation.CollectionKeyValidator;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service layer for RagCollection operations.
 * <p>
 * Handles all collection business logic including delete, restore, and clone.
 * Transactional boundaries are defined at this layer to respect the layered architecture principle
 * (transaction management belongs in the service layer, not the controller).
 */
@Service
public class RagCollectionService {

    private static final Logger log = LoggerFactory.getLogger(RagCollectionService.class);

    private final RagCollectionRepository collectionRepository;
    private final RagDocumentRepository documentRepository;
    private final CollectionIdentityResolver identityResolver;
    private final AuditLogService auditLogService;  // optional: null when audit log is unavailable
    private DocumentVersionService documentVersionService;  // optional for isolated unit tests

    @Autowired
    public RagCollectionService(RagCollectionRepository collectionRepository,
                                RagDocumentRepository documentRepository,
                                CollectionIdentityResolver identityResolver,
                                @Autowired(required = false) AuditLogService auditLogService) {
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.identityResolver = identityResolver;
        this.auditLogService = auditLogService;
    }

    @Autowired(required = false)
    public void setDocumentVersionService(DocumentVersionService documentVersionService) {
        this.documentVersionService = documentVersionService;
    }

    /**
     * Compatibility constructor for isolated unit tests and embedding clients.
     */
    public RagCollectionService(RagCollectionRepository collectionRepository,
                                RagDocumentRepository documentRepository,
                                AuditLogService auditLogService) {
        this(collectionRepository, documentRepository,
                new CollectionIdentityResolver(collectionRepository), auditLogService);
    }

    @Transactional
    public RagCollection createCollection(CollectionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String key = request.getCollectionKey();
        if (!CollectionKeyValidator.isValid(key) || key == null) {
            throw new IllegalArgumentException(
                    "collectionKey is required and must contain 1-128 visible ASCII characters");
        }
        if (collectionRepository.existsByCollectionKey(key)) {
            throw duplicateKey(key);
        }

        RagCollection collection = new RagCollection();
        collection.setCollectionKey(key);
        collection.setName(request.getName());
        collection.setDescription(request.getDescription());
        collection.setEmbeddingModel(request.getEmbeddingModel());
        collection.setDimensions(request.getDimensions() != null ? request.getDimensions() : 1024);
        collection.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        collection.setMetadata(request.getMetadata());
        try {
            return collectionRepository.saveAndFlush(collection);
        } catch (DataIntegrityViolationException e) {
            if (isCollectionKeyConstraint(e)) {
                throw duplicateKey(key, e);
            }
            throw e;
        }
    }

    /**
     * Soft-deletes a collection and unlinks legacy documents.
     *
     * <p>External-managed documents cannot be unlinked because their stable
     * identity includes the Collection. They must be explicitly purged before
     * this legacy deletion flow is allowed.
     *
     * @param id collection ID
     * @return deletion result containing unlinked document count, or empty if collection not found
     */
    @Transactional
    public Optional<DeleteResult> deleteCollection(Long id) {
        Objects.requireNonNull(id, "id must not be null");
        log.info("Soft-deleting collection: id={}", id);

        return collectionRepository.findByIdAndDeletedFalse(id)
                .map(collection -> {
                    long expectedVersion = collection.getVersion() == null
                            ? 0L : collection.getVersion();
                    long externalManaged =
                            documentRepository.countExternalManagedByCollectionId(id);
                    if (externalManaged > 0) {
                        throw new DocumentRevisionConflictException(
                                "Collection contains external-managed documents; "
                                        + "purge them explicitly before deleting the collection");
                    }
                    // Batch clear collection_id of associated documents (avoid loading one by one)
                    long count = documentRepository.countByCollectionId(id);
                    if (count > 0) {
                        documentRepository.clearCollectionIdByCollectionId(id);
                        log.info("Unlinked {} documents from collection {}", count, id);
                    }

                    int deleted = collectionRepository.softDeleteIfVersion(
                            id, expectedVersion, java.time.LocalDateTime.now());
                    if (deleted != 1) {
                        throw new DocumentRevisionConflictException(
                                "Collection changed concurrently; retry the delete");
                    }
                    log.info("Collection soft-deleted: id={}", id);

                    if (auditLogService != null) {
                        auditLogService.logDelete(AuditLogService.ENTITY_COLLECTION,
                                String.valueOf(id),
                                "Collection soft-deleted, documentsUnlinked: " + count);
                    }

                    return new DeleteResult(id, count);
                });
    }

    /**
     * Restores a soft-deleted collection.
     *
     * @param id collection ID
     * @return restored collection with document count, or empty if not found or not deleted
     */
    @Transactional
    public Optional<RestoreResult> restoreCollection(Long id) {
        Objects.requireNonNull(id, "id must not be null");
        log.info("Restoring collection: id={}", id);

        int updated = collectionRepository.restore(id);
        if (updated == 0) {
            log.warn("Collection not found or not deleted for restore: id={}", id);
            return Optional.empty();
        }

        log.info("Collection restored: id={}", id);

        if (auditLogService != null) {
            auditLogService.logUpdate(AuditLogService.ENTITY_COLLECTION,
                    String.valueOf(id),
                    "Collection restored");
        }

        return collectionRepository.findById(id)
                .map(c -> {
                    long docCount = documentRepository.countByCollectionId(id);
                    return new RestoreResult(c, docCount);
                });
    }

    /**
     * Creates a deep copy of a collection with all its documents.
     * Documents are copied with PENDING processing status (embeddings must be re-generated).
     *
     * @param id source collection ID
     * @return clone result, or empty if source collection not found
     */
    @Transactional
    public Optional<CollectionCloneResponse> cloneCollection(Long id) {
        throw new IllegalArgumentException(
                "collectionKey is required when cloning a collection");
    }

    @Transactional
    public Optional<CollectionCloneResponse> cloneCollection(Long id, String collectionKey) {
        Objects.requireNonNull(id, "id must not be null");
        if (!CollectionKeyValidator.isValid(collectionKey) || collectionKey == null) {
            throw new IllegalArgumentException(
                    "collectionKey is required and must contain 1-128 visible ASCII characters");
        }
        if (collectionRepository.existsByCollectionKey(collectionKey)) {
            throw duplicateKey(collectionKey);
        }
        log.info("Cloning collection: id={}", id);

        return identityResolver.findActive(id, null)
                .map(source -> {
                    // Build new collection as a copy
                    RagCollection cloned = new RagCollection();
                    cloned.setCollectionKey(collectionKey);
                    cloned.setName(source.getName() + " (Copy)");
                    cloned.setDescription(source.getDescription());
                    cloned.setEmbeddingModel(source.getEmbeddingModel());
                    cloned.setDimensions(source.getDimensions());
                    cloned.setEnabled(source.getEnabled());
                    cloned.setMetadata(source.getMetadata());
                    final RagCollection saved;
                    try {
                        saved = collectionRepository.saveAndFlush(cloned);
                    } catch (DataIntegrityViolationException e) {
                        if (isCollectionKeyConstraint(e)) {
                            throw duplicateKey(collectionKey, e);
                        }
                        throw e;
                    }

                    // Copy all documents (content + metadata only; embeddings require re-embedding)
                    List<RagDocument> sourceDocs = documentRepository.findAllByCollectionId(id);
                    List<RagDocument> clonedDocs = sourceDocs.stream()
                            .map(doc -> cloneDocument(doc, saved.getId()))
                            .toList();
                    if (!clonedDocs.isEmpty()) {
                        documentRepository.saveAllAndFlush(clonedDocs);
                        if (documentVersionService != null) {
                            for (int i = 0; i < clonedDocs.size(); i++) {
                                RagDocument clonedDoc = clonedDocs.get(i);
                                documentVersionService.forceRecordVersion(
                                        clonedDoc,
                                        "CREATE",
                                        "Cloned from document " + sourceDocs.get(i).getId()
                                                + " in collection " + id);
                            }
                        }
                    }

                    log.info("Collection cloned: sourceId={}, newId={}, documents={}",
                            id, saved.getId(), clonedDocs.size());

                    if (auditLogService != null) {
                        auditLogService.logCreate(AuditLogService.ENTITY_COLLECTION,
                                String.valueOf(saved.getId()),
                                "Collection cloned from " + source.getName() + " (ID: " + id + "), documents: " + clonedDocs.size(),
                                java.util.Map.of("sourceCollectionId", id,
                                        "sourceCollectionName", source.getName(),
                                        "documentsCloned", clonedDocs.size()));
                    }

                    return CollectionCloneResponse.of(
                            saved.getId(),
                            saved.getCollectionKey(),
                            saved.getName(),
                            id,
                            source.getCollectionKey(),
                            source.getName(),
                            clonedDocs.size());
                });
    }

    private RagException duplicateKey(String key) {
        return duplicateKey(key, null);
    }

    private RagException duplicateKey(String key, Throwable cause) {
        return new RagException(ErrorCode.DUPLICATE_RESOURCE,
                "Collection key already exists: " + key, cause);
    }

    private boolean isCollectionKeyConstraint(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "uk_rag_collection_collection_key".equals(violation.getConstraintName())) {
                return true;
            }
            if ("org.postgresql.util.PSQLException".equals(current.getClass().getName())) {
                try {
                    Object serverError = current.getClass()
                            .getMethod("getServerErrorMessage")
                            .invoke(current);
                    if (serverError != null
                            && "uk_rag_collection_collection_key".equals(
                            serverError.getClass().getMethod("getConstraint")
                                    .invoke(serverError))) {
                        return true;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Keep the original database error when driver details are unavailable.
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private RagDocument cloneDocument(RagDocument source, Long newCollectionId) {
        RagDocument doc = new RagDocument();
        doc.setTitle(source.getTitle());
        doc.setSource(source.getSource());
        doc.setContent(source.getContent());
        doc.setDocumentType(source.getDocumentType());
        doc.setMetadata(source.getMetadata());
        doc.setSize(source.getSize());
        doc.setContentHash(source.getContentHash());
        doc.setExternalId(source.getExternalId());
        doc.setSourceRevision(source.getSourceRevision());
        doc.setSourceDeletedAt(source.getSourceDeletedAt());
        doc.setJsonbPayload(source.getJsonbPayload() == null
                ? null : source.getJsonbPayload().deepCopy());
        doc.setOriginalFilename(source.getOriginalFilename());
        doc.setSource(source.getSource());
        doc.setCollectionId(newCollectionId);
        doc.setEnabled(source.getEnabled());
        doc.setProcessingStatus("PENDING");  // Must re-embed; embeddings not copied
        return doc;
    }

    // --- Result records ---

    public record DeleteResult(Long id, long documentsUnlinked) {}

    public record RestoreResult(RagCollection collection, long documentCount) {}
}
