package com.springairag.core.service;

import com.springairag.api.dto.CollectionCloneResponse;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
    private final AuditLogService auditLogService;  // optional: null when audit log is unavailable

    @Autowired
    public RagCollectionService(RagCollectionRepository collectionRepository,
                                RagDocumentRepository documentRepository,
                                @Autowired(required = false) AuditLogService auditLogService) {
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Soft-deletes a collection and unlinks all associated documents.
     *
     * @param id collection ID
     * @return deletion result containing unlinked document count, or empty if collection not found
     */
    @Transactional
    public Optional<DeleteResult> deleteCollection(Long id) {
        log.info("Soft-deleting collection: id={}", id);

        return collectionRepository.findByIdAndDeletedFalse(id)
                .map(collection -> {
                    // Batch clear collection_id of associated documents (avoid loading one by one)
                    long count = documentRepository.countByCollectionId(id);
                    if (count > 0) {
                        documentRepository.clearCollectionIdByCollectionId(id);
                        log.info("Unlinked {} documents from collection {}", count, id);
                    }

                    collectionRepository.softDelete(id, java.time.LocalDateTime.now());
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
        log.info("Cloning collection: id={}", id);

        return collectionRepository.findByIdAndDeletedFalse(id)
                .map(source -> {
                    // Build new collection as a copy
                    RagCollection cloned = new RagCollection();
                    cloned.setName(source.getName() + " (Copy)");
                    cloned.setDescription(source.getDescription());
                    cloned.setEmbeddingModel(source.getEmbeddingModel());
                    cloned.setDimensions(source.getDimensions());
                    cloned.setEnabled(source.getEnabled());
                    cloned.setMetadata(source.getMetadata());
                    final RagCollection saved = collectionRepository.save(cloned);

                    // Copy all documents (content + metadata only; embeddings require re-embedding)
                    List<RagDocument> sourceDocs = documentRepository.findAllByCollectionId(id);
                    List<RagDocument> clonedDocs = sourceDocs.stream()
                            .map(doc -> cloneDocument(doc, saved.getId()))
                            .toList();
                    if (!clonedDocs.isEmpty()) {
                        documentRepository.saveAll(clonedDocs);
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
                            saved.getName(),
                            id,
                            source.getName(),
                            clonedDocs.size());
                });
    }

    private RagDocument cloneDocument(RagDocument source, Long newCollectionId) {
        RagDocument doc = new RagDocument();
        doc.setTitle(source.getTitle());
        doc.setSource(source.getSource());
        doc.setContent(source.getContent());
        doc.setDocumentType(source.getDocumentType());
        doc.setMetadata(source.getMetadata());
        doc.setSize(source.getSize());
        doc.setCollectionId(newCollectionId);
        doc.setEnabled(source.getEnabled());
        doc.setProcessingStatus("PENDING");  // Must re-embed; embeddings not copied
        return doc;
    }

    // --- Result records ---

    public record DeleteResult(Long id, long documentsUnlinked) {}

    public record RestoreResult(RagCollection collection, long documentCount) {}
}
