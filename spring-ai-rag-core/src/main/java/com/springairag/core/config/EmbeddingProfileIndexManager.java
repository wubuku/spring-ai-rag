package com.springairag.core.config;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 幂等创建活动 Profile 的部分 HNSW 索引。
 */
@Component
public class EmbeddingProfileIndexManager {

    private static final int MAX_ATTEMPTS = 3;
    private final JdbcTemplate jdbcTemplate;

    public EmbeddingProfileIndexManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensureIndex(EmbeddingProfile profile) {
        if (profile.id() <= 0) {
            throw new IllegalArgumentException("Embedding profile ID must be positive");
        }
        String column = EmbeddingVectorColumns.columnFor(profile.dimensions());
        String indexName = "idx_rag_emb_p_" + profile.id() + "_" + profile.dimensions() + "_hnsw";

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ensureIndexOnce(column, indexName, profile.id());
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                try {
                    Thread.sleep(attempt * 100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while creating embedding profile index", interrupted);
                }
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("Failed to create embedding profile index")
                : lastFailure;
    }

    private void ensureIndexOnce(String column, String indexName, long profileId) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                if (indexExistsAndValid(statement, indexName)) {
                    return null;
                }
                if (indexExists(statement, indexName)) {
                    statement.execute("DROP INDEX CONCURRENTLY IF EXISTS " + indexName);
                }
                statement.execute(
                        "CREATE INDEX CONCURRENTLY IF NOT EXISTS " + indexName
                                + " ON rag_embeddings USING hnsw ("
                                + column + " vector_cosine_ops) "
                                + "WITH (m='16', ef_construction='64') "
                                + "WHERE embedding_profile_id = " + profileId
                                + " AND " + column + " IS NOT NULL");
                if (!indexExistsAndValid(statement, indexName)) {
                    throw new IllegalStateException(
                            "Embedding profile index is not valid: " + indexName);
                }
                return null;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        });
    }

    private boolean indexExists(Statement statement, String indexName) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT EXISTS (SELECT 1 FROM pg_class WHERE relkind = 'i' "
                        + "AND relname = '" + indexName + "')")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private boolean indexExistsAndValid(Statement statement, String indexName) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT i.indisvalid FROM pg_class c "
                        + "JOIN pg_index i ON i.indexrelid = c.oid "
                        + "WHERE c.relname = '" + indexName + "'")) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
