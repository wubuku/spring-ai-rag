package com.springairag.core.repository;

import com.springairag.core.entity.RagEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * RAG 向量嵌入 JPA Repository
 */
@Repository
public interface RagEmbeddingRepository extends JpaRepository<RagEmbedding, Long> {

    /**
     * 按文档 ID 查询嵌入向量
     */
    List<RagEmbedding> findByDocumentId(Long documentId);

    /**
     * 按文档 ID 统计嵌入向量数量
     */
    long countByDocumentId(Long documentId);

    /**
     * 按文档 ID 删除所有嵌入向量
     */
    void deleteByDocumentId(Long documentId);
}
