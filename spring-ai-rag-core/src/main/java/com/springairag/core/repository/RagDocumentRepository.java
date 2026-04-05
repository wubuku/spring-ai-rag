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
 * RAG 文档 JPA Repository
 */
@Repository
public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {

    /**
     * 按标题模糊搜索（忽略大小写）
     */
    Page<RagDocument> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    /**
     * 按文档类型查询
     */
    Page<RagDocument> findByDocumentType(String documentType, Pageable pageable);

    /**
     * 按处理状态查询
     */
    Page<RagDocument> findByProcessingStatus(String processingStatus, Pageable pageable);

    /**
     * 按是否启用查询
     */
    Page<RagDocument> findByEnabled(Boolean enabled, Pageable pageable);

    /**
     * 按集合 ID 查询
     */
    Page<RagDocument> findByCollectionId(Long collectionId, Pageable pageable);

    /**
     * 按集合 ID + 标题关键词搜索（忽略大小写）
     */
    Page<RagDocument> findByCollectionIdAndTitleContainingIgnoreCase(
            Long collectionId, String title, Pageable pageable);

    /**
     * 在集合内综合搜索：标题关键词 + 可选类型/状态过滤
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
     * 综合搜索：标题模糊 + 可选类型/状态过滤
     */
    @Query("SELECT d FROM RagDocument d WHERE " +
           "(COALESCE(:title, '') = '' OR LOWER(d.title) LIKE LOWER(CONCAT('%', :title, '%'))) AND " +
           "(COALESCE(:documentType, '') = '' OR d.documentType = :documentType) AND " +
           "(COALESCE(:processingStatus, '') = '' OR d.processingStatus = :processingStatus) AND " +
           "(:enabled IS NULL OR d.enabled = :enabled)")
    Page<RagDocument> searchDocuments(@Param("title") String title,
                                       @Param("documentType") String documentType,
                                       @Param("processingStatus") String processingStatus,
                                       @Param("enabled") Boolean enabled,
                                       Pageable pageable);

    /**
     * 统计各状态的文档数量
     */
    @Query("SELECT d.processingStatus, COUNT(d) FROM RagDocument d GROUP BY d.processingStatus")
    List<Object[]> countByProcessingStatus();

    /**
     * 按内容哈希查找（用于去重）
     */
    List<RagDocument> findByContentHash(String contentHash);

    /**
     * 统计集合中的文档数量
     */
    long countByCollectionId(Long collectionId);

    /**
     * 按集合 ID 查询（不分页）
     */
    List<RagDocument> findAllByCollectionId(Long collectionId);

    /**
     * 将指定集合的所有文档的 collectionId 置空（批量操作，避免逐个加载）
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE RagDocument d SET d.collectionId = NULL WHERE d.collectionId = :collectionId")
    void clearCollectionIdByCollectionId(@Param("collectionId") Long collectionId);
}
