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

    /**
     * Count fresh chunks for a document under one completed Embedding Profile.
     */
    @Query(value = "SELECT COALESCE(MAX(s.chunk_count), 0) "
            + "FROM rag_document_embedding_state s "
            + "JOIN rag_documents d ON d.id = s.document_id "
            + "WHERE s.document_id = :documentId "
            + "AND s.embedding_profile_id = :embeddingProfileId "
            + "AND s.status = 'COMPLETED' "
            + "AND s.content_hash = d.content_hash",
            nativeQuery = true)
    long countFreshChunksByDocumentIdAndProfileId(
            @Param("documentId") Long documentId,
            @Param("embeddingProfileId") long embeddingProfileId);
}
