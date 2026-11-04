package com.springairag.core.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

/**
 * 幂等创建活动 Profile 的部分 HNSW 索引。
 *
 * <p>多实例同时启动时，同一个索引可能被多个副本并发创建。PostgreSQL 的
 * {@code CREATE INDEX CONCURRENTLY} 在并发同名创建时会死锁或重复建索引：
 * 旧实现遇错后立刻 {@code DROP INDEX CONCURRENTLY} 重来，会把另一个副本
 * 正在构建的索引删掉，导致重复全量构建甚至启动失败。</p>
 *
 * <p>因此这里不依赖悲观锁，而是通过只读观测 {@code pg_stat_progress_create_index}
 * 判断"另一个副本正在构建"：只要构建在途就等待其完成；只有连续观察到索引
 * 无效且没有任何构建进度时，才把它视为上次崩溃留下的残骸并删除重建。</p>
 */
@Component
public class EmbeddingProfileIndexManager {

    private static final int MAX_ATTEMPTS = 3;
    private static final int STALE_CONFIRMATIONS = 4;
    private static final long POLL_INTERVAL_MILLIS = 250L;
    private static final long BUILD_WAIT_MILLIS = 60_000L;
    private static final String INVALID_INDEX_MESSAGE =
            "Embedding profile index is not valid: ";

    private final JdbcTemplate jdbcTemplate;
    private final int staleConfirmations;
    private final long pollIntervalMillis;
    private final long buildWaitMillis;

    @Autowired
    public EmbeddingProfileIndexManager(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, STALE_CONFIRMATIONS, POLL_INTERVAL_MILLIS, BUILD_WAIT_MILLIS);
    }

    /** 测试用：收敛轮询间隔与构建等待上限，避免真实等待。 */
    EmbeddingProfileIndexManager(JdbcTemplate jdbcTemplate,
                                 int staleConfirmations,
                                 long pollIntervalMillis,
                                 long buildWaitMillis) {
        this.jdbcTemplate = jdbcTemplate;
        this.staleConfirmations = staleConfirmations;
        this.pollIntervalMillis = pollIntervalMillis;
        this.buildWaitMillis = buildWaitMillis;
    }

    public void ensureIndex(EmbeddingProfile profile) {
        if (profile.id() <= 0) {
            throw new IllegalArgumentException("Embedding profile ID must be positive");
        }
        String column = EmbeddingVectorColumns.columnFor(profile.dimensions());
        String indexName = "idx_rag_emb_p_" + profile.id() + "_" + profile.dimensions() + "_hnsw";

        int failures = 0;
        int staleObservations = 0;
        int repairs = 0;
        long buildDeadlineNanos = 0L;
        RuntimeException lastFailure = null;

        while (true) {
            try {
                IndexStatus status = inspectIndex(indexName);
                if (status == IndexStatus.READY) {
                    return;
                }
                if (status == IndexStatus.MISSING) {
                    staleObservations = 0;
                    status = createIndex(column, indexName, profile.id());
                } else if (status == IndexStatus.INVALID) {
                    staleObservations++;
                    if (staleObservations < staleConfirmations) {
                        sleep(pollIntervalMillis);
                        continue;
                    }
                    staleObservations = 0;
                    repairs++;
                    if (repairs > MAX_ATTEMPTS) {
                        throw new IllegalStateException(INVALID_INDEX_MESSAGE + indexName);
                    }
                    status = rebuildIndex(column, indexName, profile.id());
                }

                if (status == IndexStatus.READY) {
                    return;
                }
                if (status == IndexStatus.BUILDING) {
                    staleObservations = 0;
                    if (buildDeadlineNanos == 0L) {
                        buildDeadlineNanos = System.nanoTime()
                                + TimeUnit.MILLISECONDS.toNanos(buildWaitMillis);
                    }
                    if (System.nanoTime() > buildDeadlineNanos) {
                        throw new IllegalStateException(
                                "Timed out waiting for embedding profile index build: "
                                        + indexName);
                    }
                    sleep(pollIntervalMillis);
                }
            } catch (RuntimeException e) {
                lastFailure = e;
                failures++;
                if (failures >= MAX_ATTEMPTS) {
                    throw lastFailure;
                }
                sleep(failures * 100L);
            }
        }
    }

    public void dropIndex(EmbeddingProfile profile) {
        if (profile == null || profile.id() <= 0) {
            throw new IllegalArgumentException("Embedding profile ID must be positive");
        }
        String indexName = "idx_rag_emb_p_" + profile.id() + "_"
                + profile.dimensions() + "_hnsw";
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP INDEX CONCURRENTLY IF EXISTS " + indexName);
                return null;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        });
    }

    private IndexStatus inspectIndex(String indexName) {
        return jdbcTemplate.execute((ConnectionCallback<IndexStatus>) connection -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                if (indexExistsAndValid(statement, indexName)) {
                    return IndexStatus.READY;
                }
                if (!indexExists(statement, indexName)) {
                    return IndexStatus.MISSING;
                }
                return indexBuildInProgress(statement, indexName)
                        ? IndexStatus.BUILDING
                        : IndexStatus.INVALID;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        });
    }

    private IndexStatus createIndex(String column, String indexName, long profileId) {
        return jdbcTemplate.execute((ConnectionCallback<IndexStatus>) connection -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute(createIndexSql(column, indexName, profileId));
                return confirmCreated(statement, indexName);
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        });
    }

    private IndexStatus rebuildIndex(String column, String indexName, long profileId) {
        return jdbcTemplate.execute((ConnectionCallback<IndexStatus>) connection -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                if (indexExistsAndValid(statement, indexName)) {
                    return IndexStatus.READY;
                }
                if (indexBuildInProgress(statement, indexName)) {
                    return IndexStatus.BUILDING;
                }
                statement.execute("DROP INDEX CONCURRENTLY IF EXISTS " + indexName);
                statement.execute(createIndexSql(column, indexName, profileId));
                return confirmCreated(statement, indexName);
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        });
    }

    private IndexStatus confirmCreated(Statement statement, String indexName)
            throws SQLException {
        if (indexExistsAndValid(statement, indexName)) {
            return IndexStatus.READY;
        }
        if (indexBuildInProgress(statement, indexName)) {
            return IndexStatus.BUILDING;
        }
        throw new IllegalStateException(INVALID_INDEX_MESSAGE + indexName);
    }

    private String createIndexSql(String column, String indexName, long profileId) {
        return "CREATE INDEX CONCURRENTLY IF NOT EXISTS " + indexName
                + " ON rag_embeddings USING hnsw ("
                + column + " vector_cosine_ops) "
                + "WITH (m='16', ef_construction='64') "
                + "WHERE embedding_profile_id = " + profileId
                + " AND " + column + " IS NOT NULL";
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

    /**
     * 另一个副本是否正在构建同名索引。{@code CREATE INDEX CONCURRENTLY}
     * 的建索引阶段会出现在 {@code pg_stat_progress_create_index}，这是
     * 判断"应该等待而不是重建"的唯一可靠信号，且不需要任何锁。
     */
    private boolean indexBuildInProgress(Statement statement, String indexName)
            throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_progress_create_index p "
                        + "JOIN pg_class c ON c.oid = p.index_relid "
                        + "WHERE c.relname = '" + indexName + "')")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while creating embedding profile index", interrupted);
        }
    }

    private enum IndexStatus {
        READY,
        MISSING,
        INVALID,
        BUILDING
    }
}
