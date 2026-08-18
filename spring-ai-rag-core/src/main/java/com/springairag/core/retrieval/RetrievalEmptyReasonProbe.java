package com.springairag.core.retrieval;

import com.springairag.core.config.EmbeddingProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 空结果时的有界只读计数探针。失败不得编造原因。
 */
@Component
public class RetrievalEmptyReasonProbe {

    private static final Logger log =
            LoggerFactory.getLogger(RetrievalEmptyReasonProbe.class);

    private final JdbcTemplate jdbcTemplate;

    public RetrievalEmptyReasonProbe(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Eligibility count(
            RetrievalScope scope,
            RetrievalFilters filters,
            EmbeddingProfile profile,
            int timeoutMs) {
        if (scope == null || scope.matchNone() || profile == null) {
            return Eligibility.unavailable();
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "retrieval-empty-probe");
            thread.setDaemon(true);
            return thread;
        });
        Callable<Eligibility> task = () -> query(scope, filters, profile);
        Future<Eligibility> future = executor.submit(task);
        try {
            return future.get(Math.max(100, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Empty-result diagnostic probe timed out after {}ms", timeoutMs);
            return Eligibility.probeFailed();
        } catch (ExecutionException e) {
            log.warn("Empty-result diagnostic probe failed: {}", e.getMessage());
            return Eligibility.probeFailed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Eligibility.probeFailed();
        } finally {
            executor.shutdownNow();
        }
    }

    private Eligibility query(
            RetrievalScope scope,
            RetrievalFilters filters,
            EmbeddingProfile profile) {
        RetrievalScopeSql.Fragment fragment =
                RetrievalScopeSql.buildDocumentOnly(scope, filters);
        String sql = "SELECT COUNT(*) FILTER (WHERE d.enabled = true) AS enabled_docs, "
                + "COUNT(*) FILTER (WHERE d.enabled = true "
                + "AND s.status = 'COMPLETED' "
                + "AND s.content_hash = d.content_hash "
                + "AND COALESCE(s.chunk_count, 0) > 0) AS fresh_docs "
                + "FROM rag_documents d "
                + "LEFT JOIN rag_document_embedding_state s "
                + "ON s.document_id = d.id AND s.embedding_profile_id = ? "
                + "WHERE 1 = 1 "
                + fragment.sql();
        List<Object> args = new ArrayList<>();
        args.add(profile.id());
        args.addAll(fragment.args());
        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) {
                return Eligibility.probeFailed();
            }
            return new Eligibility(
                    true,
                    false,
                    rs.getInt("enabled_docs"),
                    rs.getInt("fresh_docs"));
        }, args.toArray());
    }

    public record Eligibility(
            boolean available,
            boolean failed,
            Integer enabledDocuments,
            Integer freshDocuments) {

        public static Eligibility unavailable() {
            return new Eligibility(false, false, null, null);
        }

        public static Eligibility probeFailed() {
            return new Eligibility(false, true, null, null);
        }
    }
}
