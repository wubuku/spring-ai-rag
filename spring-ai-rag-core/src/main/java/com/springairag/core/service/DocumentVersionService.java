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
 * 文档版本历史服务
 *
 * <p>管理文档内容变更的版本记录，支持版本查询和回溯。
 *
 * <p>版本记录规则：
 * <ul>
 *   <li>CREATE — 文档首次创建时记录初始版本</li>
 *   <li>UPDATE — 内容哈希变更时记录新版本</li>
 *   <li>EMBED — 首次嵌入时记录（标记嵌入时间点）</li>
 * </ul>
 *
 * <p>相同 content_hash 不重复记录版本（避免重复嵌入产生冗余版本）。
 */
@Service
public class DocumentVersionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVersionService.class);

    private final RagDocumentVersionRepository versionRepository;

    public DocumentVersionService(RagDocumentVersionRepository versionRepository) {
        this.versionRepository = versionRepository;
    }

    /**
     * 记录文档版本（如果 content_hash 发生变更）
     *
     * @param doc        文档实体
     * @param changeType 变更类型（CREATE / UPDATE / EMBED）
     * @param description 变更描述（可选）
     * @return 创建的版本记录，如果哈希未变更则返回 empty
     */
    @Transactional
    public Optional<RagDocumentVersion> recordVersion(RagDocument doc, String changeType, String description) {
        if (doc.getId() == null || doc.getContentHash() == null) {
            log.warn("Document missing ID or contentHash, skipping version record");
            return Optional.empty();
        }

        // 检查是否已有相同哈希的版本（避免重复记录）
        List<RagDocumentVersion> existing = versionRepository
                .findByDocumentIdAndContentHash(doc.getId(), doc.getContentHash());
        if (!existing.isEmpty()) {
            log.debug("Document {} hash {} already has a version record, skipping", doc.getId(), doc.getContentHash());
            return Optional.empty();
        }

        // 计算新版本号
        int nextVersion = getNextVersionNumber(doc.getId());

        RagDocumentVersion version = RagDocumentVersion.fromDocument(doc, changeType, description);
        version.setVersionNumber(nextVersion);

        RagDocumentVersion saved = versionRepository.save(version);
        log.info("Document {} recorded version v{} ({}) hash={}", doc.getId(), nextVersion, changeType, doc.getContentHash());
        return Optional.of(saved);
    }

    /**
     * 强制记录版本（忽略哈希去重，用于重要变更点）
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
     * 查询文档版本历史（分页，最新在前）
     */
    public Page<RagDocumentVersion> getVersionHistory(Long documentId, Pageable pageable) {
        return versionRepository.findByDocumentIdOrderByVersionNumberDesc(documentId, pageable);
    }

    /**
     * 查询文档版本历史（全量，按版本号升序）
     */
    public List<RagDocumentVersion> getFullVersionHistory(Long documentId) {
        return versionRepository.findByDocumentIdOrderByVersionNumberAsc(documentId);
    }

    /**
     * 获取指定版本
     */
    public Optional<RagDocumentVersion> getVersion(Long documentId, int versionNumber) {
        return versionRepository.findByDocumentIdAndVersionNumber(documentId, versionNumber);
    }

    /**
     * 获取最新版本
     */
    public Optional<RagDocumentVersion> getLatestVersion(Long documentId) {
        return versionRepository.findLatestByDocumentId(documentId);
    }

    /**
     * 统计文档版本数
     */
    public long countVersions(Long documentId) {
        return versionRepository.countByDocumentId(documentId);
    }

    /**
     * 删除文档的所有版本记录
     */
    @Transactional
    public void deleteVersions(Long documentId) {
        versionRepository.deleteByDocumentId(documentId);
        log.info("All version records for document {} deleted", documentId);
    }

    /**
     * 计算下一个版本号
     */
    private int getNextVersionNumber(Long documentId) {
        return versionRepository.findLatestByDocumentId(documentId)
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);
    }
}
