package com.springairag.core.service;

import com.springairag.core.service.DerivationIntegrityRepository.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派生完整性快照的<strong>单判据违反</strong>矩阵（Batch 763）。
 *
 * <p>{@code from(Map)} 用一串 {@code &&} 判定"这批 chunk 在物理上是否完整"：
 * 条数相符、去重后条数相符、序号从 0 起、序号到 expected-1 止、无非法行。
 * 任何一条不满足，文档就被判为 {@code CORRUPT}，{@code DerivationRepairService}
 * 随后会安排重建。
 *
 * <p>既有测试验证的是<strong>整体状态</strong>（READY / CORRUPT / KEYWORD_ONLY 等），
 * 缺的是<strong>每条判据被单独违反</strong>时的行为。后果很具体：删掉
 * {@code local_max == expected - 1} 这一条，序号重复或断裂的 chunk 会被判为"完整"，
 * 文档永远不会被修复，而且没有任何测试会变红。
 *
 * <p>因此本类对每条判据各写一个只违反该条的用例，全部期望
 * {@code LOCAL_PHYSICAL_INTEGRITY_FAILED} / {@code VECTOR_PHYSICAL_INTEGRITY_FAILED}。
 */
class DerivationSnapshotPhysicalCriteriaTest {

    private Map<String, Object> baseRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("title", "Doc");
        row.put("version", 3L);
        row.put("document_revision", 2L);
        row.put("content_hash", "h-1");
        row.put("enabled", true);
        row.put("tombstoned", false);
        row.put("expected_chunker", "v1");
        row.put("local_status", "READY");
        row.put("local_hash", "h-1");
        row.put("local_chunker", "v1");
        row.put("local_generation", 2L);
        row.put("local_expected", 3);
        row.put("local_actual", 3);
        row.put("local_distinct", 3);
        row.put("local_min", 0);
        row.put("local_max", 2);
        row.put("local_invalid", 0);
        row.put("vector_status", "COMPLETED");
        row.put("vector_hash", "h-1");
        row.put("vector_chunker", "v1");
        row.put("vector_generation", 2L);
        row.put("vector_expected", 3);
        row.put("vector_actual", 3);
        row.put("vector_distinct", 3);
        row.put("vector_min", 0);
        row.put("vector_max", 2);
        row.put("invalid_vectors", 0);
        row.put("local_mismatches", 0);
        return row;
    }

    private Snapshot fromRow(Map<String, Object> overrides) {
        Map<String, Object> row = baseRow();
        row.putAll(overrides);
        return Snapshot.from(row);
    }

    // ==================== local 物理完整性：每条判据单独违反 ====================

    @ParameterizedTest(name = "{1} → {0}")
    @CsvSource({
            "local_expected, 0            , '期望条数为 0：没有可核对的 chunk'",
            "local_actual, 2              , '实际条数少于期望：chunk 丢失'",
            "local_actual, 4              , '实际条数多于期望：chunk 重复写入'",
            "local_distinct, 2            , '去重后条数不足：存在重复序号'",
            "local_min, 1                 , '最小序号不是 0：存在空洞'",
            "local_max, 3                 , '最大序号越过 expected-1：存在越界行'",
            "local_max, 1                 , '最大序号不足 expected-1：末尾缺失'",
            "local_invalid, 1             , '存在非法行：解析失败的 chunk'",
    })
    @DisplayName("local 判据单独被违反即判为物理损坏")
    void eachLocalCriterionViolationIsCorruption(
            String key, int value, String description) {
        Snapshot snapshot = fromRow(Map.of(key, value));

        assertTrue(snapshot.localCorrupt(),
                () -> description + " 时应判为 local 损坏");
        assertEquals("CORRUPT", snapshot.bucket(), description);
        assertEquals("LOCAL_PHYSICAL_INTEGRITY_FAILED", snapshot.reasonCode(),
                description);
        assertEquals("CORRUPT", snapshot.localCondition(), description);
    }

    @Test
    @DisplayName("全部 local 判据都满足时才算完整")
    void allLocalCriteriaSatisfiedIsComplete() {
        Snapshot snapshot = fromRow(Map.of());

        assertTrue(snapshot.localFresh(),
                "全部判据满足时 local 必须判为新鲜");
        assertFalse(snapshot.localCorrupt());
        assertEquals("READY", snapshot.bucket());
    }

    @Test
    @DisplayName("local_generation 为 0 属于物理损坏，不是普通未就绪")
    void zeroLocalGenerationIsTreatedAsCorruption() {
        // localCorrupt 的定义是「状态标称 READY 却实际不新鲜」。代数从未生成过
        // 意味着这一侧的 READY 不可信，桶必须落到 CORRUPT 以触发修复；
        // 若判成 STALE，DerivationRepairService 就不会安排重建。
        Snapshot snapshot = fromRow(Map.of("local_generation", 0L));

        assertTrue(snapshot.localCorrupt());
        assertEquals("CORRUPT", snapshot.bucket());
        assertEquals("LOCAL_PHYSICAL_INTEGRITY_FAILED", snapshot.reasonCode());
    }

    // ==================== vector 物理完整性：每条判据单独违反 ====================

    @ParameterizedTest(name = "{1} → {0}")
    @CsvSource({
            "vector_expected, 0          , '期望向量数为 0'",
            "vector_actual, 2            , '向量数少于期望'",
            "vector_actual, 4            , '向量数多于期望'",
            "vector_distinct, 2          , '向量去重后不足'",
            "vector_min, 1               , '向量序号不是从 0 起'",
            "vector_max, 3               , '向量序号越界'",
            "vector_max, 1               , '向量序号末尾缺失'",
            "invalid_vectors, 1          , '存在非法向量：维度或 NaN'",
            "local_mismatches, 1         , '向量与 chunk 不匹配：串行错位'",
    })
    @DisplayName("vector 判据单独被违反即判为物理损坏")
    void eachVectorCriterionViolationIsCorruption(
            String key, int value, String description) {
        Snapshot snapshot = fromRow(Map.of(key, value));

        assertTrue(snapshot.vectorCorrupt(),
                () -> description + " 时应判为 vector 损坏");
        assertEquals("CORRUPT", snapshot.bucket(), description);
        assertEquals("VECTOR_PHYSICAL_INTEGRITY_FAILED", snapshot.reasonCode(),
                description);
    }

    @Test
    @DisplayName("local_mismatches 只在向量侧生效，不影响 local 判据")
    void localMismatchesDoNotCorruptLocalSide() {
        // 串行错位是"向量对不上 chunk"，本地 chunk 本身仍然完整，
        // 因此原因码必须是向量侧，不能误报成需要重建本地。
        Snapshot snapshot = fromRow(Map.of("local_mismatches", 1));

        assertTrue(snapshot.localFresh(), "local 侧不受向量错位影响");
        assertEquals("VECTOR_PHYSICAL_INTEGRITY_FAILED", snapshot.reasonCode());
    }

    @Test
    @DisplayName("vector_generation 为 0 属于物理损坏")
    void zeroVectorGenerationIsTreatedAsCorruption() {
        Snapshot snapshot = fromRow(Map.of("vector_generation", 0L));

        assertTrue(snapshot.vectorCorrupt());
        assertEquals("CORRUPT", snapshot.bucket());
        assertEquals("VECTOR_PHYSICAL_INTEGRITY_FAILED", snapshot.reasonCode());
    }

    // ==================== 取值辅助函数的类型边界 ====================

    @Test
    @DisplayName("缺失的数值字段回退为默认值而非抛异常")
    void missingNumericFieldsFallBack() {
        Map<String, Object> row = baseRow();
        // 把所有物理判据依赖的数值键都删掉，模拟台账行字段缺失。
        for (String key : new String[] {
                "id", "version", "document_revision", "local_generation",
                "local_expected", "local_actual", "local_distinct",
                "local_min", "local_max", "local_invalid",
                "vector_generation", "vector_expected", "vector_actual",
                "vector_distinct", "vector_min", "vector_max",
                "invalid_vectors", "local_mismatches"}) {
            row.remove(key);
        }

        Snapshot snapshot = Snapshot.from(row);

        assertEquals(0L, snapshot.documentId());
        assertEquals(0L, snapshot.documentVersion());
        assertTrue(snapshot.localCorrupt());
    }

    @Test
    @DisplayName("字段值是字符串数字时按 Number 语义处理而非抛异常")
    void nonNumericValuesFallBackToZero() {
        // JDBC 驱动在不同列类型上可能回传 String；这里钉住"不崩溃 + 判为不完整"。
        Snapshot snapshot = fromRow(Map.of(
                "local_actual", "three",
                "local_distinct", "three"));

        assertTrue(snapshot.localCorrupt());
    }

    @Test
    @DisplayName("active_job_id 为 null 时映射为 null UUID")
    void nullActiveJobIdMapsToNull() {
        Map<String, Object> row = baseRow();
        row.put("active_job_id", null);

        assertNull(Snapshot.from(row).activeJobId());
    }

    @Test
    @DisplayName("active_job_id 为合法 UUID 字符串时正确解析")
    void validUuidStringIsParsed() {
        UUID jobId = UUID.randomUUID();
        Snapshot snapshot = fromRow(Map.of("active_job_id", jobId.toString()));

        assertEquals(jobId, snapshot.activeJobId());
    }

    @Test
    @DisplayName("active_job_id 直接给 UUID 对象时同样解析")
    void uuidInstanceIsAccepted() {
        UUID jobId = UUID.randomUUID();
        Snapshot snapshot = fromRow(Map.of("active_job_id", jobId));

        assertEquals(jobId, snapshot.activeJobId());
    }

    @Test
    @DisplayName("active_job_id 为非 UUID 字符串时降级为无任务，不中断整页扫描")
    void nonUuidJobIdDegradesToNoJob() {
        // from(Map) 是 JdbcTemplate 的 RowMapper：在这里抛异常会让整个
        // derive-readiness 查询失败，运维将看不到任何文档的就绪状态，
        // 只能看到一条报错。台账是脏数据时，正确行为是这一行降级为
        // "无任务"，其余行照常出报告。
        Snapshot snapshot = fromRow(Map.of("active_job_id", "job-42"));

        assertNull(snapshot.activeJobId(),
                "无法解析的 job id 应降级为 null，而不是让整页扫描失败");
        assertEquals("READY", snapshot.bucket(),
                "仅 job id 不可解析不应改变文档的就绪分类");
        assertEquals("CURRENT", snapshot.reasonCode());
    }

    @Test
    @DisplayName("active_job_id 不可解析时仍保留可读的作业状态")
    void nonUuidJobIdStillExposesJobStatus() {
        // jobStatus 与 activeJobId 是两列：UUID 坏了不该连带丢掉状态文本，
        // 否则运维就看不出"还有一个任务在跑"。
        Snapshot snapshot = fromRow(Map.of(
                "active_job_id", "job-42",
                "active_job_status", "RUNNING"));

        assertEquals("RUNNING", snapshot.activeJobStatus());
    }

    // ==================== 损坏优先级与下游动作 ====================

    @Test
    @DisplayName("本地与向量同时损坏时，本地原因码优先")
    void localCorruptionTakesPrecedenceOverVector() {
        Snapshot snapshot = fromRow(Map.of(
                "local_invalid", 1,
                "invalid_vectors", 1));

        assertEquals("LOCAL_PHYSICAL_INTEGRITY_FAILED", snapshot.reasonCode());
    }

    @Test
    @DisplayName("损坏且已有修复动作时，toResponse 给出重建指令")
    void corruptSnapshotRequestsRebuild() {
        Snapshot snapshot = fromRow(Map.of("local_invalid", 1));
        var response = snapshot.toResponse();

        assertTrue(response.repairable());
        assertTrue(response.recommendedActions().contains("REBUILD_LOCAL"),
                () -> "实际动作：" + response.recommendedActions());
    }

    @Test
    @DisplayName("向量损坏但本地新鲜时，只排队向量重建")
    void vectorCorruptionOnlyQueuesVectorRebuild() {
        Snapshot snapshot = fromRow(Map.of("invalid_vectors", 1));
        var response = snapshot.toResponse();

        assertTrue(response.recommendedActions().contains("QUEUE_VECTOR"),
                () -> "实际动作：" + response.recommendedActions());
        assertFalse(response.recommendedActions().contains("REBUILD_LOCAL"),
                "本地完整时不应要求重建本地");
    }
}
