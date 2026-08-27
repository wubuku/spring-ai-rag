package com.springairag.core.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * 反馈文档引用的轻量 JDBC 存储，避免在两次并发校验之间复用 JPA 一级缓存。
 */
@Component
public class FeedbackDocumentReferenceStore {

    private final JdbcTemplate jdbcTemplate;

    public FeedbackDocumentReferenceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DocumentSnapshot> load(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT id, collection_id, enabled
                FROM rag_documents
                WHERE id IN (%s)
                ORDER BY id
                """.formatted(placeholders(documentIds.size())),
                (rs, row) -> new DocumentSnapshot(
                        rs.getLong("id"),
                        rs.getObject("collection_id", Long.class),
                        rs.getBoolean("enabled")),
                documentIds.toArray());
    }

    public void insert(long feedbackId, List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO rag_user_feedback_document(feedback_id, document_id)
                VALUES (?, ?)
                """,
                documentIds,
                documentIds.size(),
                (PreparedStatement statement, Long documentId) -> {
                    statement.setLong(1, feedbackId);
                    statement.setLong(2, documentId);
                });
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    public record DocumentSnapshot(
            long documentId,
            Long collectionId,
            boolean enabled) {
    }
}
