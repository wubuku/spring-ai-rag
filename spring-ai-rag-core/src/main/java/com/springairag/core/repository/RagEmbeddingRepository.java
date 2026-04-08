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
 * RAG Vector Embedding JPA Repository
 *
 * <p>Note: methods involving the vector column use native SQL because Hibernate's
 * FloatPrimitiveArrayJavaType cannot deserialize pgvector's binary format.
 */
@Repository
public interface RagEmbeddingRepository extends JpaRepository<RagEmbedding, Long> {

    /**
     * Delete all embeddings by document ID.
     *
     * <p>Note: the hibernate-vector module provides proper pgvector type mapping,
     * so derived methods work correctly (native SQL no longer needed).
     */
    void deleteByDocumentId(Long documentId);

    /**
     * Batch delete embeddings by document ID list.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM RagEmbedding e WHERE e.documentId IN :documentIds")
    void deleteByDocumentIdIn(@Param("documentIds") List<Long> documentIds);

    /**
     * Count embeddings by document ID.
     */
    long countByDocumentId(Long documentId);
}
