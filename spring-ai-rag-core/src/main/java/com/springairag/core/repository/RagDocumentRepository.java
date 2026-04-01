package com.springairag.core.repository;

import com.springairag.core.entity.RagDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * RAG 文档 JPA Repository
 */
@Repository
public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {
}
