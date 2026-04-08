package com.springairag.core.repository;

import com.springairag.core.entity.RagDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * RAG Document JPA Repository
 */
@Repository
public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {

    /**
     * Search by title (case-insensitive).
     */
    Page<RagDocument> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    /**
     * Query by document type.
     */
    Page<RagDocument> findByDocumentType(String documentType, Pageable pageable);

    /**
     * Query by processing status.
     */
    Page<RagDocument> findByProcessingStatus(String processingStatus, Pageable pageable);

    /**
     * Query by enabled status.
     */
    Page<RagDocument> findByEnabled(Boolean enabled, Pageable pageable);

    /**
     * Query by collection ID.
     */
    Page<RagDocument> findByCollectionId(Long collectionId, Pageable pageable);

    /**
     * Search within collection: title keyword + optional type/status filter (case-insensitive).
     */
    Page<RagDocument> findByCollectionIdAndTitleContainingIgnoreCase(
            Long collectionId, String title, Pageable pageable);

    /**
     * Comprehensive search within collection: title keyword + optional type/status filter.
     */
    @Query("SELECT d FROM RagDocument d WHERE d.collectionId = :collectionId AND " +
           "(COALESCE(:keyword, '') = '' OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) AND " +
           "(COALESCE(:documentType, '') = '' OR d.documentType = :documentType) AND " +
           "(COALESCE(:processingStatus, '') = '' OR d.processingStatus = :processingStatus)")
    Page<RagDocument> searchDocumentsByCollectionId(@Param("collectionId") Long collectionId,
                                                    @Param("keyword") String keyword,
                                                    @Param("documentType") String documentType,
                                                    @Param("processingStatus") String processingStatus,
                                                    Pageable pageable);

    /**
     * Comprehensive search: title fuzzy + optional type/status filter.
     */
    @Query("SELECT d FROM RagDocument d WHERE " +
           "(COALESCE(:title, '') = '' OR LOWER(d.title) LIKE LOWER(CONCAT('%', :title, '%'))) AND " +
           "(COALESCE(:documentType, '') = '' OR d.documentType = :documentType) AND " +
           "(COALESCE(:processingStatus, '') = '' OR d.processingStatus = :processingStatus) AND " +
           "(:enabled IS NULL OR d.enabled = :enabled) AND " +
           "(:collectionId IS NULL OR d.collectionId = :collectionId)")
    Page<RagDocument> searchDocuments(@Param("title") String title,
                                       @Param("documentType") String documentType,
                                       @Param("processingStatus") String processingStatus,
                                       @Param("enabled") Boolean enabled,
                                       @Param("collectionId") Long collectionId,
                                       Pageable pageable);

    /**
     * Count documents by processing status.
     */
    @Query("SELECT d.processingStatus, COUNT(d) FROM RagDocument d GROUP BY d.processingStatus")
    List<Object[]> countByProcessingStatus();

    /**
     * Find by content hash (for deduplication).
     */
    List<RagDocument> findByContentHash(String contentHash);

    /**
     * Count documents in a collection.
     */
    long countByCollectionId(Long collectionId);

    /**
     * Query by collection ID (no pagination).
     */
    List<RagDocument> findAllByCollectionId(Long collectionId);

    /**
     * Find document IDs belonging to any of the given collection IDs (multi-collection search).
     */
    @Query("SELECT d.id FROM RagDocument d WHERE d.collectionId IN :collectionIds")
    List<Long> findIdsByCollectionIdIn(@Param("collectionIds") List<Long> collectionIds);

    /**
     * Clear collection ID for all documents in a collection (batch operation, avoids loading one by one).
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE RagDocument d SET d.collectionId = NULL WHERE d.collectionId = :collectionId")
    void clearCollectionIdByCollectionId(@Param("collectionId") Long collectionId);

    /**
     * Find documents without embeddings (no corresponding record in rag_embeddings, or embedding is NULL).
     */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT d.* FROM rag_documents d " +
                "LEFT JOIN rag_embeddings e ON d.id = e.document_id " +
                "WHERE e.id IS NULL OR e.embedding IS NULL",
        nativeQuery = true)
    List<RagDocument> findDocumentsWithoutEmbeddings();

    /**
     * Count documents without embeddings.
     */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT COUNT(*) FROM rag_documents d " +
                "LEFT JOIN rag_embeddings e ON d.id = e.document_id " +
                "WHERE e.id IS NULL OR e.embedding IS NULL",
        nativeQuery = true)
    long countDocumentsWithoutEmbeddings();
}
