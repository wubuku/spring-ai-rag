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
     * 按文档 ID 删除所有嵌入向量
     *
     * <p>注意：hibernate-vector 模块提供 pgvector 正确类型映射，
     * 派生方法可正常工作（不再需要原生 SQL）。
     */
    void deleteByDocumentId(Long documentId);

    /**
     * 按文档 ID 列表批量删除嵌入向量
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM RagEmbedding e WHERE e.documentId IN :documentIds")
    void deleteByDocumentIdIn(@Param("documentIds") List<Long> documentIds);

    /**
     * 按文档 ID 统计嵌入向量数量
     */
    long countByDocumentId(Long documentId);
}
