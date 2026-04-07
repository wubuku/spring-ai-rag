package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Document version history service.
 *
 * <p>Manages version records of document content changes, supporting version queries and rollback.
 *
 * <p>Version recording rules:
 * <ul>
 *   <li>CREATE — records initial version when document is first created</li>
 *   <li>UPDATE — records new version when content hash changes</li>
 *   <li>EMBED — records when document is first embedded (marks embedding timestamp)</li>
 * </ul>
 *
 * <p>Same content_hash does not create duplicate version records (avoids redundant versions from re-embedding).
 */
@Service
public class DocumentVersionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVersionService.class);

    private final RagDocumentVersionRepository versionRepository;

    public DocumentVersionService(RagDocumentVersionRepository versionRepository) {
        this.versionRepository = versionRepository;
    }

    /**
     * Records a document version (if content_hash has changed).
     *
     * @param doc        document entity
     * @param changeType change type (CREATE / UPDATE / EMBED)
     * @param description change description (optional)
     * @return created version record, or empty if hash has not changed
     */
    @Transactional
    public Optional<RagDocumentVersion> recordVersion(RagDocument doc, String changeType, String description) {
        if (doc.getId() == null || doc.getContentHash() == null) {
            log.warn("Document missing ID or contentHash, skipping version record");
            return Optional.empty();
        }

        // Check for existing version with same hash (avoid duplicate records)
        List<RagDocumentVersion> existing = versionRepository
                .findByDocumentIdAndContentHash(doc.getId(), doc.getContentHash());
        if (!existing.isEmpty()) {
            log.debug("Document {} hash {} already has a version record, skipping", doc.getId(), doc.getContentHash());
            return Optional.empty();
        }

        // Compute next version number
        int nextVersion = getNextVersionNumber(doc.getId());

        RagDocumentVersion version = RagDocumentVersion.fromDocument(doc, changeType, description);
        version.setVersionNumber(nextVersion);

        RagDocumentVersion saved = versionRepository.save(version);
        log.info("Document {} recorded version v{} ({}) hash={}", doc.getId(), nextVersion, changeType, doc.getContentHash());
        return Optional.of(saved);
    }

    /**
     * Force-records a version (ignores hash deduplication, for important change points).
     */
    @Transactional
    public RagDocumentVersion forceRecordVersion(RagDocument doc, String changeType, String description) {
        int nextVersion = getNextVersionNumber(doc.getId());

        RagDocumentVersion version = RagDocumentVersion.fromDocument(doc, changeType, description);
        version.setVersionNumber(nextVersion);

        RagDocumentVersion saved = versionRepository.save(version);
        log.info("Document {} force-recorded version v{} ({})", doc.getId(), nextVersion, changeType);
        return saved;
    }

    /**
     * Queries document version history (paginated, newest first).
     */
    public Page<RagDocumentVersion> getVersionHistory(Long documentId, Pageable pageable) {
        return versionRepository.findByDocumentIdOrderByVersionNumberDesc(documentId, pageable);
    }

    /**
     * Queries document version history (full, ascending by version number).
     */
    public List<RagDocumentVersion> getFullVersionHistory(Long documentId) {
        return versionRepository.findByDocumentIdOrderByVersionNumberAsc(documentId);
    }

    /**
     * Gets a specific version.
     */
    public Optional<RagDocumentVersion> getVersion(Long documentId, int versionNumber) {
        return versionRepository.findByDocumentIdAndVersionNumber(documentId, versionNumber);
    }

    /**
     * Gets the latest version.
     */
    public Optional<RagDocumentVersion> getLatestVersion(Long documentId) {
        return versionRepository.findLatestByDocumentId(documentId);
    }

    /**
     * Counts document versions.
     */
    public long countVersions(Long documentId) {
        return versionRepository.countByDocumentId(documentId);
    }

    /**
     * Deletes all version records for a document.
     */
    @Transactional
    public void deleteVersions(Long documentId) {
        versionRepository.deleteByDocumentId(documentId);
        log.info("All version records for document {} deleted", documentId);
    }

    /**
     * Computes the next version number.
     */
    private int getNextVersionNumber(Long documentId) {
        return versionRepository.findLatestByDocumentId(documentId)
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);
    }
}
