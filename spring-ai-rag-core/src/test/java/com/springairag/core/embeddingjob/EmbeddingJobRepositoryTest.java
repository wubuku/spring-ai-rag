package com.springairag.core.embeddingjob;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 Embedding Job 仓储的轻量守卫方法第一批：取消请求探测、
 * 进度/心跳写入、提交门计数与 createOrCoalesce 空结果失败。
 */
class EmbeddingJobRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private EmbeddingJobRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new EmbeddingJobRepository(jdbcTemplate);
    }

    @Test
    void isCancellationRequestedReflectsFlag() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class),
                any(Object[].class))).thenReturn(true);
        assertTrue(repository.isCancellationRequested(UUID.randomUUID()));

        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class),
                any(Object[].class))).thenReturn(null);
        assertFalse(repository.isCancellationRequested(UUID.randomUUID()));
    }

    @Test
    void markProgressReportsCasOutcome() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        assertEquals(1, repository.markProgress(
                UUID.randomUUID(), "worker-1", "CHUNKING"));

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        assertEquals(0, repository.markProgress(
                UUID.randomUUID(), "worker-1", "CHUNKING"));
    }

    @Test
    void heartbeatReportsCasOutcome() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        assertEquals(1, repository.heartbeat(UUID.randomUUID(), "worker-1", 60));

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        assertEquals(0, repository.heartbeat(UUID.randomUUID(), "worker-1", 60));
    }

    @Test
    void isCommitAllowedReflectsGuardCount() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(1L);
        assertTrue(repository.isCommitAllowed(
                UUID.randomUUID(), "worker-1", 7L));

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        assertFalse(repository.isCommitAllowed(
                UUID.randomUUID(), "worker-1", 7L));
    }

    @Test
    void createOrCoalesceFailsWhenNoRowReturned() {
        // 空结果表示 INSERT 未生成行——防御性失败。
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> repository.createOrCoalesce(
                UUID.randomUUID(), 1L, 7L, "hash", 3L, false, 8));
    }

    @Test
    void createOrCoalesceReportsCoalescedFlag() {
        // coalesced 标志由 (xmax <> 0) 判定：更新已存在行时为 true。
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenAnswer(invocation -> {
                    org.springframework.jdbc.core.RowMapper<?> mapper =
                            invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getBoolean("coalesced")).thenReturn(true);
                    // mapJob 需要 24 列；coalesced 标志是本用例唯一关注点，
                    // EmbeddingJob 分量允许为 null（record 无校验）。
                    for (String column : new String[]{
                            "id", "batch_id", "lease_owner", "last_error",
                            "origin", "requested_by_principal_id", "status",
                            "content_hash", "document_kind", "chunker_version"}) {
                        when(rs.getString(column)).thenReturn(column.equals("status")
                                ? "QUEUED" : "x");
                    }
                    when(rs.getLong("document_id")).thenReturn(1L);
                    when(rs.getLong("embedding_profile_id")).thenReturn(7L);
                    when(rs.getBoolean("force")).thenReturn(false);
                    when(rs.getLong("document_version")).thenReturn(1L);
                    when(rs.getInt("attempt_count")).thenReturn(0);
                    when(rs.getInt("max_attempts")).thenReturn(8);
                    when(rs.getLong("request_generation")).thenReturn(1L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        EmbeddingJobRepository.CreateResult result = repository.createOrCoalesce(
                UUID.randomUUID(), 1L, 7L, "hash", 3L, false, 8);

        assertTrue(result.coalesced());
    }

    @Test
    void createOrCoalesceWithOriginDefaultsLegacyColumns() {
        // 9 参重载委托 12 参主体：origin/principal 透传，代际固定 1、
        // 文档种类与 chunker 版本回退 legacy 兼容默认值。
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                args.capture())).thenAnswer(invocation -> {
                    org.springframework.jdbc.core.RowMapper<?> mapper =
                            invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getBoolean("coalesced")).thenReturn(false);
                    for (String column : new String[]{
                            "id", "batch_id", "lease_owner", "last_error",
                            "origin", "requested_by_principal_id", "status",
                            "content_hash", "document_kind", "chunker_version"}) {
                        when(rs.getString(column)).thenReturn(column.equals("status")
                                ? "QUEUED" : "x");
                    }
                    when(rs.getLong("document_id")).thenReturn(1L);
                    when(rs.getLong("embedding_profile_id")).thenReturn(7L);
                    when(rs.getBoolean("force")).thenReturn(false);
                    when(rs.getLong("document_version")).thenReturn(1L);
                    when(rs.getInt("attempt_count")).thenReturn(0);
                    when(rs.getInt("max_attempts")).thenReturn(8);
                    when(rs.getLong("request_generation")).thenReturn(1L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        EmbeddingJobRepository.CreateResult result = repository.createOrCoalesce(
                UUID.randomUUID(), 1L, 7L, "hash", 3L, false, 8,
                "API", "db:unit");

        assertEquals(EmbeddingJobStatus.QUEUED, result.job().status());
        // captor 命中 varargs 槽位：getValue() 只含 varargs——
        // [0]=id、[8]=origin、[9]=principal、[10]=generation、
        // [11]=documentKind、[12]=chunkerVersion。
        assertEquals("API", args.getValue()[8]);
        assertEquals("db:unit", args.getValue()[9]);
        assertEquals(1L, args.getValue()[10]);
        assertEquals("TEXT", args.getValue()[11]);
        assertEquals("legacy-compatible", args.getValue()[12]);
    }

    // ── readiness/listPage/字段 rowMapper（Batch 357 第四批）──────────

    @Test
    void readinessMapsCountsAndBindsChunkerVersions() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.ResultSetExtractor.class),
                args.capture())).thenAnswer(invocation -> {
                    org.springframework.jdbc.core.ResultSetExtractor<?> extractor =
                            invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getLong("enabled_docs")).thenReturn(6L);
                    when(rs.getLong("fresh_docs")).thenReturn(3L);
                    when(rs.getLong("queued_docs")).thenReturn(1L);
                    when(rs.getLong("running_docs")).thenReturn(1L);
                    when(rs.getLong("failed_docs")).thenReturn(0L);
                    when(rs.getLong("stale_docs")).thenReturn(1L);
                    return extractor.extractData(rs);
                });

        var profile = new com.springairag.core.config.EmbeddingProfile(
                7L, "bge-m3", "zhipu", "bge-m3", "v1", 1024,
                "COSINE", "NONE", true);
        var response = repository.readiness(
                42L, "coll-key", profile, "text-v1", "json-v2");

        assertEquals("coll-key", response.collectionKey());
        assertEquals("bge-m3", response.activeEmbeddingProfileKey());
        assertEquals(6L, response.enabledDocuments());
        assertEquals(3L, response.freshDocuments());
        assertEquals(1L, response.queuedDocuments());
        assertEquals(1L, response.runningDocuments());
        assertEquals(0L, response.failedDocuments());
        assertEquals(1L, response.staleOrMissingDocuments());
        // 参数顺序：json chunker → text chunker → collectionId → profileId。
        assertEquals("json-v2", args.getValue()[0]);
        assertEquals("text-v1", args.getValue()[1]);
        assertEquals(42L, args.getValue()[2]);
        assertEquals(7L, args.getValue()[3]);
    }

    @Test
    void listPageReturnsZeroTotalWhenCountQueryReturnsNull() {
        // count 查询返回 null（理论上不该发生）→ totalElements 归 0。
        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*)"),
                eq(Long.class), any(Object[].class))).thenReturn(null);
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        EmbeddingJobRepository.PageResult page = repository.listPage(
                null, null, null, null, 50, 0);

        assertEquals(0, page.totalElements());
        assertTrue(page.items().isEmpty());
    }

    private java.sql.ResultSet fullJobRow(UUID id) throws java.sql.SQLException {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getObject("batch_id", UUID.class))
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-00000000000f"));
        when(rs.getLong("document_id")).thenReturn(11L);
        when(rs.getLong("embedding_profile_id")).thenReturn(7L);
        when(rs.getBoolean("force")).thenReturn(true);
        when(rs.getString("content_hash")).thenReturn("hash-1");
        when(rs.getLong("document_version")).thenReturn(4L);
        when(rs.getString("status")).thenReturn("FAILED");
        when(rs.getInt("attempt_count")).thenReturn(2);
        when(rs.getInt("max_attempts")).thenReturn(8);
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        when(rs.getObject("available_at", java.time.OffsetDateTime.class))
                .thenReturn(now);
        when(rs.getString("lease_owner")).thenReturn("worker-9");
        when(rs.getObject("lease_expires_at", java.time.OffsetDateTime.class))
                .thenReturn(now);
        when(rs.getObject("cancel_requested_at", java.time.OffsetDateTime.class))
                .thenReturn(now);
        when(rs.getString("last_error")).thenReturn("boom");
        when(rs.getObject("created_at", java.time.OffsetDateTime.class))
                .thenReturn(now);
        when(rs.getObject("started_at", java.time.OffsetDateTime.class))
                .thenReturn(now);
        when(rs.getObject("finished_at", java.time.OffsetDateTime.class))
                .thenReturn(now);
        when(rs.getObject("updated_at", java.time.OffsetDateTime.class))
                .thenReturn(now);
        when(rs.getString("origin")).thenReturn("API");
        when(rs.getString("requested_by_principal_id")).thenReturn("p1");
        when(rs.getLong("request_generation")).thenReturn(3L);
        when(rs.getString("document_kind")).thenReturn("TEXT");
        when(rs.getString("chunker_version")).thenReturn("v2");
        return rs;
    }

    @Test
    void fieldRowMapperMapsAllJobColumns() {
        // 经 find 走字段级 rowMapper（与 createOrCoalesce 内联 mapper
        // 不同实例），验证 24 列全字段映射。
        UUID jobId = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenAnswer(invocation -> {
                    org.springframework.jdbc.core.RowMapper<?> mapper =
                            invocation.getArgument(1);
                    return List.of(mapper.mapRow(fullJobRow(jobId), 0));
                });

        EmbeddingJob job = repository.find(jobId).orElseThrow();

        assertEquals(jobId, job.id());
        assertEquals(11L, job.documentId());
        assertEquals(7L, job.embeddingProfileId());
        assertTrue(job.force());
        assertEquals("hash-1", job.contentHash());
        assertEquals(4L, job.documentVersion());
        assertEquals(EmbeddingJobStatus.FAILED, job.status());
        assertEquals(2, job.attemptCount());
        assertEquals(8, job.maxAttempts());
        assertEquals("worker-9", job.leaseOwner());
        assertEquals("boom", job.lastError());
        assertEquals("API", job.origin());
        assertEquals("p1", job.requestedByPrincipalId());
        assertEquals(3L, job.requestGeneration());
        assertEquals("TEXT", job.documentKind());
        assertEquals("v2", job.chunkerVersion());
    }

    // ── 清扫第二扫：cancel/查找/代际/提交门/文档状态（Batch 358）──────

    @Test
    void claimRefreshesStateForCancelledAndFailedJobs() {
        // 取消与失败两段回收查询的 UPDATE..RETURNING id 行映射真实
        // 执行，回收到的每个 job 都要刷新 embedding state。
        UUID cancelId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger call =
                new java.util.concurrent.atomic.AtomicInteger();
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class)))
                .thenAnswer(invocation -> {
                    org.springframework.jdbc.core.RowMapper<?> mapper =
                            invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(
                            call.incrementAndGet() == 1 ? cancelId : failedId);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.query(contains("WITH candidates"),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        assertTrue(repository.claim("worker-1", 5, 60).isEmpty());

        verify(jdbcTemplate).update(contains("SET status = CASE job.status"),
                eq(cancelId));
        verify(jdbcTemplate).update(contains("SET status = CASE job.status"),
                eq(failedId));
    }

    @Test
    void cancelReturnsJobAndRefreshesState() {
        UUID jobId = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenAnswer(invocation -> {
                    org.springframework.jdbc.core.RowMapper<?> mapper =
                            invocation.getArgument(1);
                    return List.of(mapper.mapRow(fullJobRow(jobId), 0));
                });
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        assertTrue(repository.cancel(jobId).isPresent());
        verify(jdbcTemplate).update(contains("SET status = CASE job.status"),
                eq(jobId));

        // 无行返回 → 空 Optional，且不触发状态刷新。
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        assertTrue(repository.cancel(jobId).isEmpty());
    }

    @Test
    void findActiveAndFindCurrentActiveReturnFirstRow() {
        EmbeddingJob job = job();
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of(job));

        assertTrue(repository.findActive(11L, 7L, "hash-1").isPresent());

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                args.capture())).thenReturn(List.of(job));
        assertTrue(repository.findCurrentActive(
                11L, 7L, "hash-1", "TEXT", "v2").isPresent());
        // 参数顺序：documentId → profileId → contentHash →
        // documentKind → chunkerVersion。
        assertEquals(11L, args.getValue()[0]);
        assertEquals(7L, args.getValue()[1]);
        assertEquals("hash-1", args.getValue()[2]);
        assertEquals("TEXT", args.getValue()[3]);
        assertEquals("v2", args.getValue()[4]);

        // 空行 → 空 Optional（findActive 分支）。
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        assertTrue(repository.findActive(11L, 7L, "hash-1").isEmpty());
    }

    @Test
    void allocateGenerationBindsFiltersAndDefaultsNullToOne() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                args.capture())).thenReturn(5L);

        assertEquals(5L, repository.allocateGeneration(
                11L, 7L, "hash-1", "v2", true));
        // 参数顺序：documentId → profileId → contentHash →
        // chunkerVersion → preserveCompleted×2。
        assertEquals(11L, args.getValue()[0]);
        assertEquals(7L, args.getValue()[1]);
        assertEquals("hash-1", args.getValue()[2]);
        assertEquals("v2", args.getValue()[3]);
        assertEquals(Boolean.TRUE, args.getValue()[4]);
        assertEquals(Boolean.TRUE, args.getValue()[5]);

        // RETURNING 为 null（理论上不该发生）→ 代际回退 1。
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(null);
        assertEquals(1L, repository.allocateGeneration(
                11L, 7L, "hash-1", "v2", false));
    }

    @Test
    void markNotRequestedCancelsSupersededAndDefaultsGeneration() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(null);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        assertEquals(1L, repository.markNotRequested(11L, 7L, "hash-1", "v2"));
        // cancelSuperseded 以回退代际 1 取消旧代任务。
        verify(jdbcTemplate).update(contains("Superseded by a newer"),
                eq(11L), eq(7L), eq(1L));
    }

    @Test
    void claimCommitAllowedReflectsCasOutcomeAndFloorsLease() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                args.capture())).thenAnswer(invocation -> {
                    // 提交门行映射常量 1，真实执行映射 lambda。
                    org.springframework.jdbc.core.RowMapper<?> mapper =
                            invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    return List.of(mapper.mapRow(rs, 0));
                });
        UUID jobId = UUID.randomUUID();

        assertTrue(repository.claimCommitAllowed(jobId, "worker-1", 7L, 5));
        // 租约秒下限 30；参数顺序：lease → jobId → workerId → profileId。
        assertEquals(30, args.getValue()[0]);
        assertEquals(jobId, args.getValue()[1]);
        assertEquals("worker-1", args.getValue()[2]);
        assertEquals(7L, args.getValue()[3]);

        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        assertFalse(repository.claimCommitAllowed(jobId, "worker-1", 7L, 60));
    }

    @Test
    void updateDocumentProcessingBindsStatusErrorAndDocument() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        repository.updateDocumentProcessing(11L, "FAILED", "boom");

        verify(jdbcTemplate).update(contains("processing_status = ?"),
                eq("FAILED"), eq("boom"), eq(11L));
    }

    @Test
    void columnHelpersSwallowSQLExceptionToNullAndZero() throws Exception {
        // origin 列读取抛 SQLException → columnOrNull 归 null；
        // request_generation 列抛 SQLException → longColumnOrZero 归 0。
        UUID jobId = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenAnswer(invocation -> {
                    org.springframework.jdbc.core.RowMapper<?> mapper =
                            invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(jobId);
                    when(rs.getObject("batch_id", UUID.class)).thenReturn(jobId);
                    when(rs.getLong("document_id")).thenReturn(11L);
                    when(rs.getLong("embedding_profile_id")).thenReturn(7L);
                    when(rs.getBoolean("force")).thenReturn(false);
                    when(rs.getString("content_hash")).thenReturn("h");
                    when(rs.getLong("document_version")).thenReturn(1L);
                    when(rs.getString("status")).thenReturn("QUEUED");
                    when(rs.getInt("attempt_count")).thenReturn(0);
                    when(rs.getInt("max_attempts")).thenReturn(8);
                    when(rs.getString("origin"))
                            .thenThrow(new java.sql.SQLException("bad col"));
                    when(rs.getLong("request_generation"))
                            .thenThrow(new java.sql.SQLException("bad col"));
                    return List.of(mapper.mapRow(rs, 0));
                });

        EmbeddingJob job = repository.find(jobId).orElseThrow();

        assertNull(job.origin());
        assertNull(job.requestedByPrincipalId());
        assertEquals(0L, job.requestGeneration());
    }

    @Test
    void listPageBindsAllFiltersForCountAndItems() {
        UUID batchId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(9L);
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        EmbeddingJobRepository.PageResult page = repository.listPage(
                batchId, EmbeddingJobStatus.RUNNING, 42L, null, 500, -5);

        assertEquals(9L, page.totalElements());
        // count 参数：batchId → status → collectionId。
        verify(jdbcTemplate).queryForObject(
                contains("job.batch_id = ?"), eq(Long.class),
                eq(batchId), eq("RUNNING"), eq(42L));
        // item 参数：batchId → status → collectionId → pageSize(≤200)
        // → offset(≥0)。
        verify(jdbcTemplate).query(contains("ORDER BY job.created_at DESC"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(batchId), eq("RUNNING"), eq(42L), eq(200), eq(0));
    }

    // ── claim/租约生命周期（Batch 116 第二批）──────────────────────────

    private EmbeddingJob job() {
        return new EmbeddingJob(
                UUID.randomUUID(), UUID.randomUUID(), 1L, 7L, false, "hash",
                3L, EmbeddingJobStatus.QUEUED, 0, 8,
                java.time.OffsetDateTime.now(), null, null, null, null, null,
                null, null, null, "manual", null, 1L, "TEXT", "legacy-compatible");
    }

    @Test
    void claimCancelsRequestedJobsAndFailsExhaustedJobsBeforeClaiming() {
        // 取消/失败两段回收查询返回空；认领查询命中一个候选。
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        EmbeddingJob job = job();
        when(jdbcTemplate.query(contains("WITH candidates"),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of(job));

        List<EmbeddingJob> claimed = repository.claim("worker-1", 5, 60);

        assertEquals(1, claimed.size());
        // 认领后把文档状态推进为 PROCESSING 并绑定 active_job_id。
        verify(jdbcTemplate).update(contains("active_job_id = ?"),
                any(Object[].class));
    }

    @Test
    void claimFloorsLimitAndLeaseSeconds() {
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.query(contains("WITH candidates"),
                any(org.springframework.jdbc.core.RowMapper.class),
                args.capture())).thenReturn(List.of());

        repository.claim("worker-1", 0, 5);

        // 参数顺序：LIMIT（下限 1）→ workerId → 租约秒（下限 30）。
        assertEquals(1, args.getValue()[0]);
        assertEquals("worker-1", args.getValue()[1]);
        assertEquals(30, args.getValue()[2]);
    }

    @Test
    void claimByIdMarksStateProcessingWhenClaimed() {
        EmbeddingJob job = job();
        when(jdbcTemplate.query(contains("SET status = 'RUNNING',"),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of(job));

        var claimed = repository.claimById(job.id(), "worker-1", 60);

        assertTrue(claimed.isPresent());
        assertEquals(EmbeddingJobStatus.QUEUED, claimed.get().status());
        verify(jdbcTemplate).update(contains("active_job_id = ?"),
                any(Object[].class));
    }

    @Test
    void claimByIdReturnsEmptyWhenJobNotClaimable() {
        when(jdbcTemplate.query(contains("SET status = 'RUNNING',"),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        assertTrue(repository.claimById(UUID.randomUUID(), "worker-1", 60)
                .isEmpty());
    }

    // ── 终态方法（Batch 117 第三批）───────────────────────────────────

    @Test
    void markSucceededOverloadDefaultsForceSatisfiedToTrue() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        UUID id = UUID.randomUUID();

        assertEquals(1, repository.markSucceeded(id, "worker-1"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(
                contains("SET status = 'SUCCEEDED'"), args.capture());
        assertEquals(id, args.getValue()[0]);
        assertEquals(Boolean.TRUE, args.getValue()[2]);
    }

    @Test
    void markStaleAndMarkCancelledDelegateToTerminalUpdate() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        UUID id = UUID.randomUUID();

        assertEquals(1, repository.markStale(id, "worker-1", "generation changed"));
        verify(jdbcTemplate).update(contains("SET status = ?"), eq("STALE"),
                eq("generation changed"), eq(id), eq("worker-1"));

        assertEquals(1, repository.markCancelled(id, "worker-1"));
        verify(jdbcTemplate).update(contains("SET status = ?"), eq("CANCELLED"),
                org.mockito.ArgumentMatchers.isNull(), eq(id), eq("worker-1"));
    }

    @Test
    void markFailureFloorsBackoffSecondsAtOne() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        UUID id = UUID.randomUUID();

        assertEquals(1, repository.markFailure(id, "worker-1", "boom", -5));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(
                contains("SET status = CASE"), args.capture());
        // 退避秒数下限 1：负值不会产生非法间隔。
        assertEquals(1, args.getValue()[0]);
        assertEquals("boom", args.getValue()[1]);
    }

    @Test
    void refreshStateFromJobDelegatesWithJobId() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        UUID jobId = UUID.randomUUID();

        repository.refreshStateFromJob(jobId);

        verify(jdbcTemplate).update(
                contains("SET status = CASE job.status"), eq(jobId));
    }

    @Test
    void retryResetsAttemptsAndReturnsJob() {
        EmbeddingJob job = job();
        when(jdbcTemplate.query(contains("SET status = 'QUEUED',"),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of(job));

        var retried = repository.retry(job.id(), 12);

        assertTrue(retried.isPresent());
        assertEquals(EmbeddingJobStatus.QUEUED, retried.get().status());
        // retry 走 UPDATE...RETURNING 查询；maxAttempts 绑定到查询参数。
        verify(jdbcTemplate).query(
                contains("SET status = 'QUEUED'"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(12), eq(job.id()));
    }
}
