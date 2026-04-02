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
 * 文档版本历史 Repository
 */
@Repository
public interface RagDocumentVersionRepository extends JpaRepository<RagDocumentVersion, Long> {

    /**
     * 查询文档的所有版本（按版本号降序）
     */
    Page<RagDocumentVersion> findByDocumentIdOrderByVersionNumberDesc(Long documentId, Pageable pageable);

    /**
     * 查询文档的所有版本（按版本号升序）
     */
    List<RagDocumentVersion> findByDocumentIdOrderByVersionNumberAsc(Long documentId);

    /**
     * 查询文档的指定版本
     */
    Optional<RagDocumentVersion> findByDocumentIdAndVersionNumber(Long documentId, int versionNumber);

    /**
     * 查询文档的最新版本
     */
    @Query("SELECT v FROM RagDocumentVersion v WHERE v.documentId = :documentId ORDER BY v.versionNumber DESC LIMIT 1")
    Optional<RagDocumentVersion> findLatestByDocumentId(@Param("documentId") Long documentId);

    /**
     * 统计文档版本数
     */
    long countByDocumentId(Long documentId);

    /**
     * 删除文档的所有版本
     */
    void deleteByDocumentId(Long documentId);

    /**
     * 按 contentHash 查询（用于去重判断）
     */
    List<RagDocumentVersion> findByDocumentIdAndContentHash(Long documentId, String contentHash);
}
