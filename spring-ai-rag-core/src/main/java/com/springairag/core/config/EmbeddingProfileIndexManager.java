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

    private static final long INDEX_LOCK_KEY = 7_611_024_001L;
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

        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_lock(" + INDEX_LOCK_KEY + ")");
                try {
                    if (indexExistsAndValid(statement, indexName)) {
                        return null;
                    }
                    if (indexExists(statement, indexName)) {
                        statement.execute("DROP INDEX CONCURRENTLY " + indexName);
                    }
                    statement.execute(
                            "CREATE INDEX CONCURRENTLY " + indexName
                                    + " ON rag_embeddings USING hnsw ("
                                    + column + " vector_cosine_ops) "
                                    + "WITH (m='16', ef_construction='64') "
                                    + "WHERE embedding_profile_id = " + profile.id()
                                    + " AND " + column + " IS NOT NULL");
                    if (!indexExistsAndValid(statement, indexName)) {
                        throw new IllegalStateException(
                                "Embedding profile index is not valid: " + indexName);
                    }
                    return null;
                } finally {
                    statement.execute("SELECT pg_advisory_unlock(" + INDEX_LOCK_KEY + ")");
                }
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
