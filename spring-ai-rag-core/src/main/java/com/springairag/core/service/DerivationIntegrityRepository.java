package com.springairag.core.service;

import com.springairag.api.dto.DerivationReadinessDocument;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.EmbeddingVectorColumns;
import com.springairag.core.entity.RagDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * local/vector freshness 的共享物理真相源。
 *
 * <p>查询通过 lateral 聚合一次核对当前 generation 的行数、连续索引、描述符、文本、
 * position 与向量维度，不把 READY/COMPLETED 状态本身当成完整性证明。
 */
@Repository
public class DerivationIntegrityRepository {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProfileProvider profileProvider;
    private final DocumentDerivationDescriptorProvider descriptorProvider;

    public DerivationIntegrityRepository(
            JdbcTemplate jdbcTemplate,
            EmbeddingProfileProvider profileProvider,
            DocumentDerivationDescriptorProvider descriptorProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.profileProvider = profileProvider;
        this.descriptorProvider = descriptorProvider;
    }

    public Snapshot inspect(RagDocument document) {
        return inspect(document.getId());
    }

    public Snapshot inspect(long documentId) {
        List<Snapshot> rows = query(
                "document.id = ?", List.of(documentId), "document.id", null, null);
        return rows.isEmpty() ? Snapshot.missing(documentId) : rows.getFirst();
    }

    public List<Snapshot> scanCollection(long collectionId) {
        return query("document.collection_id = ?", List.of(collectionId),
                "document.id", null, null);
    }

    public List<Snapshot> scanCollection(
            long collectionId,
            String bucket,
            int offset,
            int size) {
        List<Long> ids = classifiedIds(collectionId, bucket, Set.of(), Set.of(),
                false, offset, size);
        return inspectIds(ids);
    }

    public List<Snapshot> scanRepairCandidates(
            long collectionId,
            Set<String> buckets,
            Set<String> vectorConditions,
            int size) {
        List<Long> ids = classifiedIds(collectionId, null, buckets, vectorConditions,
                true, 0, size);
        return inspectIds(ids);
    }

    public long countRepairSelection(
            long collectionId,
            Set<String> buckets,
            Set<String> vectorConditions) {
        ClassificationQuery classification = classificationQuery(collectionId);
        List<String> predicates = new ArrayList<>();
        List<Object> args = new ArrayList<>(classification.args());
        appendSelectionPredicates(predicates, args, buckets, vectorConditions);
        String where = predicates.isEmpty()
                ? "" : " WHERE " + String.join(" AND ", predicates);
        Long count = jdbcTemplate.queryForObject(
                classification.sql() + " SELECT COUNT(*) FROM bucketed" + where,
                Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public long countCollection(long collectionId, String bucket) {
        ClassificationQuery classification = classificationQuery(collectionId);
        String sql = classification.sql()
                + " SELECT COUNT(*) FROM bucketed"
                + (bucket == null ? "" : " WHERE bucket = ?");
        List<Object> args = new ArrayList<>(classification.args());
        if (bucket != null) {
            args.add(bucket);
        }
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public Aggregate aggregateCollection(long collectionId) {
        ClassificationQuery classification = classificationQuery(collectionId);
        return jdbcTemplate.query(classification.sql() + """
                SELECT
                    COUNT(*) FILTER (WHERE enabled AND NOT tombstoned) AS enabled_documents,
                    COUNT(*) FILTER (WHERE bucket = 'READY') AS ready_documents,
                    COUNT(*) FILTER (WHERE bucket = 'KEYWORD_ONLY') AS keyword_only_documents,
                    COUNT(*) FILTER (WHERE bucket = 'INDEXING') AS indexing_documents,
                    COUNT(*) FILTER (WHERE bucket = 'LOCAL_UNAVAILABLE') AS local_unavailable_documents,
                    COUNT(*) FILTER (WHERE enabled AND NOT tombstoned
                        AND NOT vector_fresh AND NOT converging)
                        AS vector_repair_needed_documents,
                    COUNT(*) FILTER (WHERE bucket = 'NOT_REQUESTED') AS not_requested_documents,
                    COUNT(*) FILTER (WHERE bucket = 'CORRUPT') AS corrupt_documents,
                    COUNT(*) FILTER (WHERE bucket = 'DISABLED') AS disabled_documents
                FROM bucketed
                """, rs -> {
            rs.next();
            return new Aggregate(
                    rs.getLong("enabled_documents"),
                    rs.getLong("ready_documents"),
                    rs.getLong("keyword_only_documents"),
                    rs.getLong("indexing_documents"),
                    rs.getLong("local_unavailable_documents"),
                    rs.getLong("vector_repair_needed_documents"),
                    rs.getLong("not_requested_documents"),
                    rs.getLong("corrupt_documents"),
                    rs.getLong("disabled_documents"));
        }, classification.args().toArray());
    }

    public EmbeddingAggregate aggregateEmbeddingReadiness(long collectionId) {
        ClassificationQuery classification = classificationQuery(collectionId);
        return jdbcTemplate.query(classification.sql() + """
                , embedding_bucketed AS (
                    SELECT *, CASE
                        WHEN vector_fresh THEN 'fresh'
                        WHEN active_job_status = 'RUNNING' AND converging THEN 'running'
                        WHEN active_job_status = 'QUEUED' AND converging THEN 'queued'
                        WHEN vector_status = 'FAILED'
                         AND vector_hash IS NOT DISTINCT FROM content_hash
                         AND vector_chunker IS NOT DISTINCT FROM expected_chunker THEN 'failed'
                        ELSE 'stale'
                    END AS embedding_bucket
                    FROM bucketed
                    WHERE enabled AND NOT tombstoned
                )
                SELECT
                    COUNT(*) AS enabled_documents,
                    COUNT(*) FILTER (WHERE embedding_bucket = 'fresh') AS fresh_documents,
                    COUNT(*) FILTER (WHERE embedding_bucket = 'queued') AS queued_documents,
                    COUNT(*) FILTER (WHERE embedding_bucket = 'running') AS running_documents,
                    COUNT(*) FILTER (WHERE embedding_bucket = 'failed') AS failed_documents,
                    COUNT(*) FILTER (WHERE embedding_bucket = 'stale') AS stale_documents
                FROM embedding_bucketed
                """, rs -> {
            rs.next();
            return new EmbeddingAggregate(
                    rs.getLong("enabled_documents"),
                    rs.getLong("fresh_documents"),
                    rs.getLong("queued_documents"),
                    rs.getLong("running_documents"),
                    rs.getLong("failed_documents"),
                    rs.getLong("stale_documents"));
        }, classification.args().toArray());
    }

    private List<Long> classifiedIds(
            long collectionId,
            String bucket,
            Set<String> buckets,
            Set<String> vectorConditions,
            boolean repairableOnly,
            int offset,
            int size) {
        ClassificationQuery classification = classificationQuery(collectionId);
        List<String> predicates = new ArrayList<>();
        List<Object> args = new ArrayList<>(classification.args());
        if (bucket != null) {
            predicates.add("bucket = ?");
            args.add(bucket);
        }
        appendSelectionPredicates(
                predicates, args, buckets, vectorConditions);
        if (repairableOnly) {
            predicates.add("enabled AND NOT tombstoned"
                    + " AND bucket NOT IN ('READY', 'INDEXING')"
                    + " AND NOT (local_fresh AND vector_condition = 'INDEXING')");
        }
        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        args.add(size);
        args.add(offset);
        return jdbcTemplate.queryForList(classification.sql()
                + " SELECT id FROM bucketed" + where + " ORDER BY id LIMIT ? OFFSET ?",
                Long.class, args.toArray());
    }

    private static void appendSelectionPredicates(
            List<String> predicates,
            List<Object> args,
            Set<String> buckets,
            Set<String> vectorConditions) {
        if (buckets.isEmpty() && vectorConditions.isEmpty()) {
            return;
        }
        List<String> selected = new ArrayList<>();
        if (!buckets.isEmpty()) {
            selected.add("bucket IN (" + placeholders(buckets.size()) + ")");
            args.addAll(buckets);
        }
        if (!vectorConditions.isEmpty()) {
            selected.add("vector_condition IN ("
                    + placeholders(vectorConditions.size()) + ")");
            args.addAll(vectorConditions);
        }
        predicates.add("(" + String.join(" OR ", selected) + ")");
    }

    private List<Snapshot> inspectIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Long> stableIds = new ArrayList<>(new LinkedHashSet<>(ids));
        return query("document.id IN (" + placeholders(stableIds.size()) + ")",
                new ArrayList<>(stableIds), "document.id", null, null);
    }

    private ClassificationQuery classificationQuery(long collectionId) {
        EmbeddingProfile profile = profileProvider.getActiveProfile();
        String vectorColumn = EmbeddingVectorColumns.columnFor(profile.dimensions());
        String sql = """
                WITH raw AS (
                    SELECT document.id, document.enabled,
                           document.source_deleted_at IS NOT NULL AS tombstoned,
                           document.content_hash,
                           CASE WHEN document.document_type = 'json-record'
                                THEN ? ELSE ? END AS expected_chunker,
                           local.local_index_status AS local_status,
                           local.content_hash AS local_hash,
                           local.chunker_version AS local_chunker,
                           local.local_index_generation AS local_generation,
                           local.chunk_count AS local_expected,
                           COALESCE(local_rows.actual_count, 0) AS local_actual,
                           COALESCE(local_rows.distinct_indexes, 0) AS local_distinct,
                           local_rows.min_index AS local_min,
                           local_rows.max_index AS local_max,
                           COALESCE(local_rows.invalid_rows, 0) AS local_invalid,
                           vector.status AS vector_status,
                           vector.content_hash AS vector_hash,
                           vector.chunker_version AS vector_chunker,
                           vector.request_generation AS vector_generation,
                           vector.chunk_count AS vector_expected,
                           COALESCE(vector_rows.actual_count, 0) AS vector_actual,
                           COALESCE(vector_rows.distinct_indexes, 0) AS vector_distinct,
                           vector_rows.min_index AS vector_min,
                           vector_rows.max_index AS vector_max,
                           COALESCE(vector_rows.invalid_vectors, 0) AS invalid_vectors,
                           COALESCE(vector_rows.local_mismatches, 0) AS local_mismatches,
                           job.status AS active_job_status
                    FROM rag_documents document
                    LEFT JOIN rag_document_local_index_state local
                      ON local.document_id = document.id
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS actual_count,
                               COUNT(DISTINCT chunk.chunk_index) AS distinct_indexes,
                               MIN(chunk.chunk_index) AS min_index,
                               MAX(chunk.chunk_index) AS max_index,
                               COUNT(*) FILTER (WHERE
                                   chunk.content_hash IS DISTINCT FROM local.content_hash
                                   OR chunk.chunker_version IS DISTINCT FROM local.chunker_version
                                   OR BTRIM(chunk.chunk_text) = ''
                                   OR chunk.chunk_start_pos < 0
                                   OR chunk.chunk_end_pos < chunk.chunk_start_pos
                               ) AS invalid_rows
                        FROM rag_document_chunks chunk
                        WHERE chunk.document_id = document.id
                          AND chunk.local_index_generation = local.local_index_generation
                    ) local_rows ON TRUE
                    LEFT JOIN rag_document_embedding_state vector
                      ON vector.document_id = document.id
                     AND vector.embedding_profile_id = ?
                    LEFT JOIN rag_embedding_jobs job
                      ON job.id = vector.active_job_id
                     AND job.document_id = document.id
                     AND job.embedding_profile_id = vector.embedding_profile_id
                     AND job.request_generation = vector.request_generation
                     AND job.content_hash = vector.content_hash
                     AND job.chunker_version = vector.chunker_version
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS actual_count,
                               COUNT(DISTINCT embedding.chunk_index) AS distinct_indexes,
                               MIN(embedding.chunk_index) AS min_index,
                               MAX(embedding.chunk_index) AS max_index,
                               COUNT(*) FILTER (WHERE embedding.%s IS NULL
                                   OR vector_dims(embedding.%s) <> ?) AS invalid_vectors,
                               COUNT(*) FILTER (WHERE current_chunk.id IS NULL
                                   OR current_chunk.chunk_text IS DISTINCT FROM embedding.chunk_text
                                   OR current_chunk.chunk_start_pos IS DISTINCT FROM embedding.chunk_start_pos
                                   OR current_chunk.chunk_end_pos IS DISTINCT FROM embedding.chunk_end_pos
                               ) AS local_mismatches
                        FROM rag_embeddings embedding
                        LEFT JOIN rag_document_chunks current_chunk
                          ON current_chunk.document_id = embedding.document_id
                         AND current_chunk.local_index_generation = local.local_index_generation
                         AND current_chunk.chunk_index = embedding.chunk_index
                        WHERE embedding.document_id = document.id
                          AND embedding.embedding_profile_id = ?
                    ) vector_rows ON TRUE
                    WHERE document.collection_id = ?
                ), local_evaluated AS (
                    SELECT *, COALESCE(
                        local_status = 'READY'
                        AND content_hash = local_hash
                        AND expected_chunker = local_chunker
                        AND local_generation > 0
                        AND local_expected > 0
                        AND local_actual = local_expected
                        AND local_distinct = local_expected
                        AND local_min = 0
                        AND local_max = local_expected - 1
                        AND local_invalid = 0, FALSE) AS local_fresh
                    FROM raw
                ), vector_evaluated AS (
                    SELECT *,
                           local_status = 'READY' AND NOT local_fresh AS local_corrupt,
                           COALESCE(
                               local_fresh
                               AND vector_status = 'COMPLETED'
                               AND content_hash = vector_hash
                               AND expected_chunker = vector_chunker
                               AND vector_generation > 0
                               AND vector_expected > 0
                               AND vector_actual = vector_expected
                               AND vector_distinct = vector_expected
                               AND vector_min = 0
                               AND vector_max = vector_expected - 1
                               AND invalid_vectors = 0
                               AND local_mismatches = 0, FALSE) AS vector_fresh,
                           COALESCE(
                               active_job_status IN ('QUEUED', 'RUNNING')
                               AND content_hash = vector_hash
                               AND expected_chunker = vector_chunker, FALSE) AS converging
                    FROM local_evaluated
                ), bucketed AS (
                    SELECT *,
                           CASE
                               WHEN NOT enabled OR tombstoned THEN 'DISABLED'
                               WHEN local_corrupt
                                 OR (vector_status = 'COMPLETED' AND NOT vector_fresh)
                                   THEN 'CORRUPT'
                               WHEN local_fresh AND vector_fresh THEN 'READY'
                               WHEN local_fresh THEN 'KEYWORD_ONLY'
                               WHEN converging THEN 'INDEXING'
                               WHEN local_status = 'NOT_REQUESTED'
                                AND (vector_status IS NULL OR vector_status = 'NOT_REQUESTED')
                                   THEN 'NOT_REQUESTED'
                               ELSE 'LOCAL_UNAVAILABLE'
                           END AS bucket,
                           CASE
                               WHEN vector_fresh THEN 'READY'
                               WHEN vector_status = 'COMPLETED' THEN 'CORRUPT'
                               WHEN converging THEN 'INDEXING'
                               WHEN vector_status IS NULL OR vector_status = 'NOT_REQUESTED'
                                   THEN 'NOT_REQUESTED'
                               WHEN vector_status IN ('FAILED', 'CANCELLED') THEN 'FAILED'
                               ELSE 'STALE'
                           END AS vector_condition
                    FROM vector_evaluated
                )
                """.formatted(vectorColumn, vectorColumn);
        return new ClassificationQuery(sql, List.of(
                descriptorProvider.jsonRecordDescriptor().chunkerVersion(),
                descriptorProvider.textDescriptor().chunkerVersion(),
                profile.id(), profile.dimensions(), profile.id(), collectionId));
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private List<Snapshot> query(
            String predicate,
            List<Object> predicateArgs,
            String orderBy,
            Integer limit,
            Integer offset) {
        EmbeddingProfile profile = profileProvider.getActiveProfile();
        String vectorColumn = EmbeddingVectorColumns.columnFor(profile.dimensions());
        String textChunker = descriptorProvider.textDescriptor().chunkerVersion();
        String jsonChunker = descriptorProvider.jsonRecordDescriptor().chunkerVersion();
        String sql = """
                SELECT document.id, document.title, document.version,
                       document.document_revision, document.content_hash,
                       document.document_type, document.enabled,
                       document.source_deleted_at, document.source_namespace,
                       document.external_id,
                       local.local_index_status, local.content_hash AS local_hash,
                       local.chunker_version AS local_chunker,
                       local.local_index_generation AS local_generation,
                       local.chunk_count AS local_expected,
                       local.processing_error AS local_error,
                       COALESCE(local_rows.actual_count, 0) AS local_actual,
                       COALESCE(local_rows.distinct_indexes, 0) AS local_distinct,
                       local_rows.min_index AS local_min,
                       local_rows.max_index AS local_max,
                       COALESCE(local_rows.invalid_rows, 0) AS local_invalid,
                       vector.status AS vector_status,
                       vector.content_hash AS vector_hash,
                       vector.chunker_version AS vector_chunker,
                       vector.request_generation AS vector_generation,
                       vector.chunk_count AS vector_expected,
                       vector.processing_error AS vector_error,
                       vector.active_job_id,
                       job.status AS active_job_status,
                       COALESCE(vector_rows.actual_count, 0) AS vector_actual,
                       COALESCE(vector_rows.distinct_indexes, 0) AS vector_distinct,
                       vector_rows.min_index AS vector_min,
                       vector_rows.max_index AS vector_max,
                       COALESCE(vector_rows.invalid_vectors, 0) AS invalid_vectors,
                       COALESCE(vector_rows.local_mismatches, 0) AS local_mismatches,
                       CASE WHEN document.document_type = 'json-record'
                            THEN ? ELSE ? END AS expected_chunker
                FROM rag_documents document
                LEFT JOIN rag_document_local_index_state local
                  ON local.document_id = document.id
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS actual_count,
                           COUNT(DISTINCT chunk.chunk_index) AS distinct_indexes,
                           MIN(chunk.chunk_index) AS min_index,
                           MAX(chunk.chunk_index) AS max_index,
                           COUNT(*) FILTER (WHERE
                               chunk.content_hash IS DISTINCT FROM local.content_hash
                               OR chunk.chunker_version IS DISTINCT FROM local.chunker_version
                               OR BTRIM(chunk.chunk_text) = ''
                               OR chunk.chunk_start_pos < 0
                               OR chunk.chunk_end_pos < chunk.chunk_start_pos
                           ) AS invalid_rows
                    FROM rag_document_chunks chunk
                    WHERE chunk.document_id = document.id
                      AND chunk.local_index_generation = local.local_index_generation
                ) local_rows ON TRUE
                LEFT JOIN rag_document_embedding_state vector
                  ON vector.document_id = document.id
                 AND vector.embedding_profile_id = ?
                LEFT JOIN rag_embedding_jobs job
                  ON job.id = vector.active_job_id
                 AND job.document_id = document.id
                 AND job.embedding_profile_id = vector.embedding_profile_id
                 AND job.request_generation = vector.request_generation
                 AND job.content_hash = vector.content_hash
                 AND job.chunker_version = vector.chunker_version
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS actual_count,
                           COUNT(DISTINCT embedding.chunk_index) AS distinct_indexes,
                           MIN(embedding.chunk_index) AS min_index,
                           MAX(embedding.chunk_index) AS max_index,
                           COUNT(*) FILTER (WHERE embedding.%s IS NULL
                               OR vector_dims(embedding.%s) <> ?) AS invalid_vectors,
                           COUNT(*) FILTER (WHERE current_chunk.id IS NULL
                               OR current_chunk.chunk_text IS DISTINCT FROM embedding.chunk_text
                               OR current_chunk.chunk_start_pos IS DISTINCT FROM embedding.chunk_start_pos
                               OR current_chunk.chunk_end_pos IS DISTINCT FROM embedding.chunk_end_pos
                           ) AS local_mismatches
                    FROM rag_embeddings embedding
                    LEFT JOIN rag_document_chunks current_chunk
                      ON current_chunk.document_id = embedding.document_id
                     AND current_chunk.local_index_generation = local.local_index_generation
                     AND current_chunk.chunk_index = embedding.chunk_index
                    WHERE embedding.document_id = document.id
                      AND embedding.embedding_profile_id = ?
                ) vector_rows ON TRUE
                WHERE %s
                ORDER BY %s
                """.formatted(vectorColumn, vectorColumn, predicate, orderBy);
        List<Object> args = new ArrayList<>();
        args.add(jsonChunker);
        args.add(textChunker);
        args.add(profile.id());
        args.add(profile.dimensions());
        args.add(profile.id());
        args.addAll(predicateArgs);
        if (limit != null) {
            sql += " LIMIT ?";
            args.add(limit);
        }
        if (offset != null) {
            sql += " OFFSET ?";
            args.add(offset);
        }
        return jdbcTemplate.query(sql, (rs, rowNum) -> Snapshot.from(Map.ofEntries(
                Map.entry("id", rs.getLong("id")),
                Map.entry("title", rs.getString("title")),
                Map.entry("version", rs.getLong("version")),
                Map.entry("document_revision", rs.getLong("document_revision")),
                Map.entry("content_hash", nullable(rs.getString("content_hash"))),
                Map.entry("enabled", rs.getBoolean("enabled")),
                Map.entry("source_deleted", rs.getObject("source_deleted_at") != null),
                Map.entry("source_namespace", nullable(rs.getString("source_namespace"))),
                Map.entry("external_id", nullable(rs.getString("external_id"))),
                Map.entry("expected_chunker", nullable(rs.getString("expected_chunker"))),
                Map.entry("local_status", nullable(rs.getString("local_index_status"))),
                Map.entry("local_hash", nullable(rs.getString("local_hash"))),
                Map.entry("local_chunker", nullable(rs.getString("local_chunker"))),
                Map.entry("local_generation", rs.getLong("local_generation")),
                Map.entry("local_expected", rs.getInt("local_expected")),
                Map.entry("local_actual", rs.getInt("local_actual")),
                Map.entry("local_distinct", rs.getInt("local_distinct")),
                Map.entry("local_min", nullableNumber(rs.getObject("local_min"))),
                Map.entry("local_max", nullableNumber(rs.getObject("local_max"))),
                Map.entry("local_invalid", rs.getInt("local_invalid")),
                Map.entry("local_error", nullable(rs.getString("local_error"))),
                Map.entry("vector_status", nullable(rs.getString("vector_status"))),
                Map.entry("vector_hash", nullable(rs.getString("vector_hash"))),
                Map.entry("vector_chunker", nullable(rs.getString("vector_chunker"))),
                Map.entry("vector_generation", rs.getLong("vector_generation")),
                Map.entry("vector_expected", rs.getInt("vector_expected")),
                Map.entry("vector_actual", rs.getInt("vector_actual")),
                Map.entry("vector_distinct", rs.getInt("vector_distinct")),
                Map.entry("vector_min", nullableNumber(rs.getObject("vector_min"))),
                Map.entry("vector_max", nullableNumber(rs.getObject("vector_max"))),
                Map.entry("invalid_vectors", rs.getInt("invalid_vectors")),
                Map.entry("local_mismatches", rs.getInt("local_mismatches")),
                Map.entry("vector_error", nullable(rs.getString("vector_error"))),
                Map.entry("active_job_id", nullable(rs.getObject("active_job_id"))),
                Map.entry("active_job_status", nullable(rs.getString("active_job_status")))
        )), args.toArray());
    }

    private static Object nullable(Object value) {
        return value == null ? NullValue.INSTANCE : value;
    }

    private static Object nullableNumber(Object value) {
        return value == null ? NullValue.INSTANCE : ((Number) value).longValue();
    }

    private enum NullValue { INSTANCE }

    public record Aggregate(
            long enabledDocuments,
            long readyDocuments,
            long keywordOnlyDocuments,
            long indexingDocuments,
            long localUnavailableDocuments,
            long vectorRepairNeededDocuments,
            long notRequestedDocuments,
            long corruptDocuments,
            long disabledDocuments) {
    }

    public record EmbeddingAggregate(
            long enabledDocuments,
            long freshDocuments,
            long queuedDocuments,
            long runningDocuments,
            long failedDocuments,
            long staleDocuments) {
    }

    private record ClassificationQuery(String sql, List<Object> args) {
    }

    public record Snapshot(
            long documentId,
            String title,
            long documentVersion,
            long documentRevision,
            String contentHash,
            boolean enabled,
            boolean tombstoned,
            String sourceNamespace,
            String externalId,
            String expectedChunker,
            String localStatus,
            String localHash,
            String localChunker,
            long localGeneration,
            int localExpected,
            int localActual,
            String localError,
            boolean localFresh,
            boolean localCorrupt,
            String vectorStatus,
            String vectorHash,
            String vectorChunker,
            long vectorGeneration,
            int vectorExpected,
            int vectorActual,
            String vectorError,
            UUID activeJobId,
            String activeJobStatus,
            boolean vectorFresh,
            boolean vectorCorrupt,
            String bucket,
            String localCondition,
            String vectorCondition,
            String reasonCode) {

        static Snapshot from(Map<String, Object> row) {
            String contentHash = value(row, "content_hash");
            String expectedChunker = value(row, "expected_chunker");
            String localStatus = value(row, "local_status");
            int localExpected = integer(row, "local_expected");
            int localActual = integer(row, "local_actual");
            long localGeneration = integer(row, "local_generation");
            boolean localRowsComplete = localExpected > 0
                    && localActual == localExpected
                    && integer(row, "local_distinct") == localExpected
                    && longValue(row, "local_min", -1) == 0
                    && longValue(row, "local_max", -1) == localExpected - 1L
                    && integer(row, "local_invalid") == 0;
            boolean localFresh = "READY".equals(localStatus)
                    && contentHash != null && contentHash.equals(value(row, "local_hash"))
                    && expectedChunker != null
                    && expectedChunker.equals(value(row, "local_chunker"))
                    && localGeneration > 0 && localRowsComplete;
            boolean localCorrupt = "READY".equals(localStatus) && !localFresh;

            String vectorStatus = value(row, "vector_status");
            int vectorExpected = integer(row, "vector_expected");
            int vectorActual = integer(row, "vector_actual");
            long vectorGeneration = integer(row, "vector_generation");
            boolean vectorRowsComplete = vectorExpected > 0
                    && vectorActual == vectorExpected
                    && integer(row, "vector_distinct") == vectorExpected
                    && longValue(row, "vector_min", -1) == 0
                    && longValue(row, "vector_max", -1) == vectorExpected - 1L
                    && integer(row, "invalid_vectors") == 0
                    && integer(row, "local_mismatches") == 0;
            boolean vectorFresh = localFresh && "COMPLETED".equals(vectorStatus)
                    && contentHash != null && contentHash.equals(value(row, "vector_hash"))
                    && expectedChunker != null
                    && expectedChunker.equals(value(row, "vector_chunker"))
                    && vectorGeneration > 0 && vectorRowsComplete;
            boolean vectorCorrupt = "COMPLETED".equals(vectorStatus) && !vectorFresh;
            boolean enabled = Boolean.TRUE.equals(row.get("enabled"));
            String jobStatus = value(row, "active_job_status");
            boolean converging = ("QUEUED".equals(jobStatus) || "RUNNING".equals(jobStatus))
                    && contentHash != null && contentHash.equals(value(row, "vector_hash"))
                    && expectedChunker != null
                    && expectedChunker.equals(value(row, "vector_chunker"));

            String localCondition = localFresh ? "READY"
                    : localCorrupt ? "CORRUPT"
                    : localStatus == null ? "MISSING"
                    : "FAILED".equals(localStatus) ? "FAILED"
                    : "NOT_REQUESTED".equals(localStatus) ? "NOT_REQUESTED" : "STALE";
            String vectorCondition = vectorFresh ? "READY"
                    : vectorCorrupt ? "CORRUPT"
                    : converging ? "INDEXING"
                    : vectorStatus == null || "NOT_REQUESTED".equals(vectorStatus)
                        ? "NOT_REQUESTED"
                        : "FAILED".equals(vectorStatus) || "CANCELLED".equals(vectorStatus)
                            ? "FAILED" : "STALE";
            String bucket;
            boolean tombstoned = Boolean.TRUE.equals(row.get("source_deleted"));
            if (!enabled || tombstoned) {
                bucket = "DISABLED";
            } else if (localCorrupt || vectorCorrupt) {
                bucket = "CORRUPT";
            } else if (localFresh && vectorFresh) {
                bucket = "READY";
            } else if (localFresh) {
                bucket = "KEYWORD_ONLY";
            } else if (converging) {
                bucket = "INDEXING";
            } else if ("NOT_REQUESTED".equals(localCondition)
                    && "NOT_REQUESTED".equals(vectorCondition)) {
                bucket = "NOT_REQUESTED";
            } else {
                bucket = "LOCAL_UNAVAILABLE";
            }
            String reason = localCorrupt ? "LOCAL_PHYSICAL_INTEGRITY_FAILED"
                    : vectorCorrupt ? "VECTOR_PHYSICAL_INTEGRITY_FAILED"
                    : !localFresh ? "LOCAL_" + localCondition
                    : !vectorFresh ? "VECTOR_" + vectorCondition : "CURRENT";
            return new Snapshot(
                    longValue(row, "id", 0), value(row, "title"), longValue(row, "version", 0),
                    longValue(row, "document_revision", 0), contentHash, enabled,
                    tombstoned,
                    value(row, "source_namespace"), value(row, "external_id"), expectedChunker,
                    localStatus, value(row, "local_hash"), value(row, "local_chunker"),
                    localGeneration, localExpected, localActual, value(row, "local_error"),
                    localFresh, localCorrupt, vectorStatus, value(row, "vector_hash"),
                    value(row, "vector_chunker"), vectorGeneration, vectorExpected,
                    vectorActual, value(row, "vector_error"), uuid(row.get("active_job_id")),
                    jobStatus, vectorFresh, vectorCorrupt, bucket, localCondition,
                    vectorCondition, reason);
        }

        static Snapshot missing(long id) {
            return new Snapshot(id, null, 0, 0, null, false, false,
                    null, null, null, null, null, null, 0, 0, 0, null,
                    false, false, null, null, null, 0, 0, 0, null, null,
                    null, false, false, "DISABLED", "MISSING", "NOT_REQUESTED",
                    "DOCUMENT_MISSING");
        }

        public DerivationReadinessDocument toResponse() {
            List<String> actions = new ArrayList<>();
            if (enabled && !tombstoned && !"INDEXING".equals(bucket)) {
                if (!localFresh) {
                    actions.add("REBUILD_LOCAL");
                }
                if (!vectorFresh && !"INDEXING".equals(vectorCondition)) {
                    actions.add("QUEUE_VECTOR");
                }
            }
            String error = localError != null ? localError : vectorError;
            if (error != null && error.length() > 500) {
                error = error.substring(0, 500);
            }
            return new DerivationReadinessDocument(
                    documentId, title, documentRevision, sourceNamespace, externalId,
                    bucket, localCondition, localGeneration, localExpected, localActual,
                    vectorCondition, vectorGeneration, vectorExpected, vectorActual,
                    activeJobId, activeJobStatus, reasonCode, error,
                    !actions.isEmpty(),
                    List.copyOf(actions));
        }

        private static String value(Map<String, Object> row, String key) {
            Object value = row.get(key);
            return value == null || value == NullValue.INSTANCE ? null : String.valueOf(value);
        }

        private static int integer(Map<String, Object> row, String key) {
            Object value = row.get(key);
            return value instanceof Number number ? number.intValue() : 0;
        }

        private static long longValue(Map<String, Object> row, String key, long fallback) {
            Object value = row.get(key);
            return value instanceof Number number ? number.longValue() : fallback;
        }

        private static UUID uuid(Object value) {
            if (value == null || value == NullValue.INSTANCE) {
                return null;
            }
            return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
        }
    }
}
