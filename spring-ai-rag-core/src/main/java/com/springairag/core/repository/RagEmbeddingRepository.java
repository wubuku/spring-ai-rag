package com.springairag.core.repository;

import com.springairag.core.entity.RagEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * RAG 向量嵌入 JPA Repository
 *
 * <p>注意：涉及 vector 列的方法使用原生 SQL，因为 Hibernate 的 FloatPrimitiveArrayJavaType
 * 无法反序列化 pgvector 的二进制格式。
 */
@Repository
public interface RagEmbeddingRepository extends JpaRepository<RagEmbedding, Long> {

    /**
     * 按文档 ID 删除所有嵌入向量（原生 SQL，避免 Hibernate 反序列化 vector 列）
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM rag_embeddings WHERE document_id = :documentId", nativeQuery = true)
    void deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * 按文档 ID 统计嵌入向量数量（原生 SQL）
     */
    @Query(value = "SELECT COUNT(*) FROM rag_embeddings WHERE document_id = :documentId", nativeQuery = true)
    long countByDocumentId(@Param("documentId") Long documentId);

    /**
     * 按文档 ID 查询嵌入向量数量（使用 JPA，不读取 vector 列）
     */
    @Query("SELECT COUNT(e) FROM RagEmbedding e WHERE e.documentId = :documentId")
    long countByDocumentIdJpa(@Param("documentId") Long documentId);
}
