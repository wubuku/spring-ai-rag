package com.springairag.core.repository;

import com.springairag.core.entity.RagDocumentVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Document Version History Repository
 */
@Repository
public interface RagDocumentVersionRepository extends JpaRepository<RagDocumentVersion, Long> {

    /**
     * Query all versions of a document (ordered by version number descending).
     */
    Page<RagDocumentVersion> findByDocumentIdOrderByVersionNumberDesc(Long documentId, Pageable pageable);

    /**
     * Query all versions of a document (ordered by version number ascending).
     */
    List<RagDocumentVersion> findByDocumentIdOrderByVersionNumberAsc(Long documentId);

    /**
     * Query a specific version of a document.
     */
    Optional<RagDocumentVersion> findByDocumentIdAndVersionNumber(Long documentId, int versionNumber);

    /**
     * Query the latest version of a document.
     */
    @Query("SELECT v FROM RagDocumentVersion v WHERE v.documentId = :documentId ORDER BY v.versionNumber DESC LIMIT 1")
    Optional<RagDocumentVersion> findLatestByDocumentId(@Param("documentId") Long documentId);

    /**
     * Count document versions.
     */
    long countByDocumentId(Long documentId);

    /**
     * Delete all versions of a document.
     */
    void deleteByDocumentId(Long documentId);

    /**
     * Query by content hash (for deduplication check).
     */
    List<RagDocumentVersion> findByDocumentIdAndContentHash(Long documentId, String contentHash);
}
