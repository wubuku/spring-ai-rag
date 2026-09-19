package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * CollectionPurgeService 校验与收尾长尾（Batch 520，JaCoCo 驱动）：
 * validateFrozenRequest 冻结字段失配、validatePreviewable 三类冲突
 * 与零计数放行、COMPLETED 预览的坏/好结果负载、空文档计划 + 修复
 * ID 的 apply 全链路（含 countUuidJoin / deleteByIds 执行与跳过分
 * 支、markCollectionRetired、json 序列化）。
 */
class CollectionPurgeValidateTailTest {

    private static final UUID PREVIEW_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID REPAIR_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");

    private JdbcTemplate jdbcTemplate;
    private RagCollectionRepository collectionRepository;
    private RagProperties ragProperties;
    private CollectionPurgeService service;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionRepository = mock(RagCollectionRepository.class);
        ragProperties = new RagProperties();
        service = new CollectionPurgeService(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules(),
                collectionRepository,
                mock(CollectionPurgeAuthorization.class),
                ragProperties,
                mock(PlatformTransactionManager.class));
        Mockito.lenient().when(jdbcTemplate.update(
                anyString(), any(Object[].class))).thenReturn(1);
        Mockito.lenient().when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        RagCollection collection = collection();
        when(collectionRepository.findByCollectionKey("kb"))
                .thenReturn(Optional.of(collection));
        when(collectionRepository.findById(10L))
                .thenReturn(Optional.of(collection));
    }

    private RagCollection collection() {
        RagCollection collection = new RagCollection();
        collection.setId(10L);
        collection.setCollectionKey("kb");
        return collection;
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticatedPrincipalType",
                "ENVIRONMENT_ROOT");
        return request;
    }

    private void stubRepairIdQuery() {
        when(jdbcTemplate.query(
                contains("FROM rag_derivation_repair_previews"),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject(1, UUID.class)).thenReturn(REPAIR_ID);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    /** 反射执行 buildPlan + fingerprint，得到与运行时一致的指纹。 */
    private String currentFingerprint() throws Exception {
        RagCollection collection = collection();
        Method build = CollectionPurgeService.class.getDeclaredMethod(
                "buildPlan", RagCollection.class, long.class, long.class);
        build.setAccessible(true);
        Object plan = build.invoke(service, collection, 5L, 2L);
        Method fingerprint = CollectionPurgeService.class.getDeclaredMethod(
                "fingerprint", RagCollection.class, plan.getClass(),
                long.class, long.class);
        fingerprint.setAccessible(true);
        return (String) fingerprint.invoke(service, collection, plan, 5L, 2L);
    }

    private void stubPreviewRow(String fingerprint, String status,
                                String resultPayload) {
        String tokenHash = com.springairag.core.util.DigestUtils
                .sha256("token-1");
        Instant future = Instant.now().plusSeconds(3_600);
        when(jdbcTemplate.query(
                contains("FROM rag_collection_purge_preview"),
                any(RowMapper.class), eq(PREVIEW_ID),
                eq("root:environment-root"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(PREVIEW_ID);
                    when(rs.getString("owner_principal_id"))
                            .thenReturn("root:environment-root");
                    when(rs.getLong("collection_id")).thenReturn(10L);
                    when(rs.getString("collection_key")).thenReturn("kb");
                    when(rs.getLong("collection_version")).thenReturn(5L);
                    when(rs.getLong("chat_commit_fence_version"))
                            .thenReturn(2L);
                    when(rs.getString("confirmation_token_hash"))
                            .thenReturn(tokenHash);
                    when(rs.getString("fingerprint")).thenReturn(fingerprint);
                    when(rs.getString("status")).thenReturn(status);
                    when(rs.getTimestamp("preview_deadline"))
                            .thenReturn(java.sql.Timestamp.from(future));
                    when(rs.getTimestamp("operation_deadline"))
                            .thenReturn(java.sql.Timestamp.from(future));
                    when(rs.getString("result_payload"))
                            .thenReturn(resultPayload);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private com.springairag.api.dto.CollectionPurgeApplyRequest applyRequest(
            String fingerprint, long expectedVersion) {
        return new com.springairag.api.dto.CollectionPurgeApplyRequest(
                "kb", PREVIEW_ID, "token-1", fingerprint,
                expectedVersion, 2L);
    }

    @Test
    void applyRejectsFrozenVersionMismatch() throws Exception {
        stubPreviewRow(currentFingerprint(), "PREVIEWED", null);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(currentFingerprint(), 6L),
                        request()));
        assertTrue(error.getMessage()
                .contains("does not match the request"));
    }

    @Test
    void completedPreviewRejectsCorruptStoredResult() {
        stubPreviewRow("unused-fingerprint", "COMPLETED", "{not-json");

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest("unused-fingerprint", 5L),
                        request()));
        assertTrue(error.getMessage()
                .contains("Stored Collection purge result is invalid"));
    }

    @Test
    void completedPreviewReturnsParsedStoredResult() throws Exception {
        String payload = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(
                        new com.springairag.api.dto.CollectionPurgeResultResponse(
                                PREVIEW_ID, "RETIRED", 10L, "kb",
                                0, 0, 0, null, null, 6L));
        stubPreviewRow("unused-fingerprint", "COMPLETED", payload);

        var result = service.apply(
                applyRequest("unused-fingerprint", 5L), request());

        assertEquals("RETIRED", result.status());
        assertEquals(6L, result.collectionVersion());
    }

    @Test
    void applyRetiresCollectionWithRepairTargetsAndEmptyDocuments()
            throws Exception {
        stubRepairIdQuery();
        String fingerprint = currentFingerprint();
        stubPreviewRow(fingerprint, "PREVIEWED", null);
        // 文档删除行数与计划计数（0）一致。
        when(jdbcTemplate.update(
                contains("DELETE FROM rag_documents"),
                any(Object[].class))).thenReturn(0);
        // 收尾结果状态行。
        when(jdbcTemplate.queryForObject(
                contains("SELECT deleted_at, purged_at, version"),
                any(RowMapper.class), eq(10L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getTimestamp("deleted_at")).thenReturn(
                            java.sql.Timestamp.from(Instant.now()));
                    when(rs.getTimestamp("purged_at")).thenReturn(
                            java.sql.Timestamp.from(Instant.now()));
                    when(rs.getLong("version")).thenReturn(6L);
                    return mapper.mapRow(rs, 0);
                });

        var result = service.apply(applyRequest(fingerprint, 5L), request());

        assertEquals("RETIRED", result.status());
        // 修复预览 ID 非空 → deleteByIds 执行分支与 countUuidJoin 均被触发。
        verify(jdbcTemplate).update(
                contains("DELETE FROM rag_derivation_repair_previews"),
                any(Object[].class));
        verify(jdbcTemplate).update(
                contains("SET status = 'COMPLETED'"), any(Object[].class));
    }

    @Test
    void validatePreviewableRejectsUnindexedContentReferences()
            throws Exception {
        Object plan = planWith(24, 1L);

        var error = assertThrows(RagException.class,
                () -> invokeValidatePreviewable(plan));
        assertTrue(error.getMessage()
                .contains("Content reference indexes are incomplete"));
    }

    @Test
    void validatePreviewableRejectsActiveWork() throws Exception {
        Object plan = planWith(21, 1L);

        var error = assertThrows(RagException.class,
                () -> invokeValidatePreviewable(plan));
        assertTrue(error.getMessage()
                .contains("active work or Chat sessions"));
    }

    @Test
    void validatePreviewableRejectsLimitOverflow() throws Exception {
        ragProperties.getCollectionPurge().setMaxDocuments(0);
        Object plan = planWith(0, 1L);

        var error = assertThrows(RagException.class,
                () -> invokeValidatePreviewable(plan));
        assertTrue(error.getMessage()
                .contains("exceeds configured synchronous limits"));
    }

    @Test
    void validatePreviewablePassesWithZeroCounts() throws Exception {
        Object plan = planWith(-1, 0L);
        assertDoesNotThrow(() -> invokeValidatePreviewable(plan));
    }

    private Object planWith(int index, long value) throws Exception {
        Class<?> countsClass = Class.forName(
                "com.springairag.core.service.CollectionPurgeService$Counts");
        long[] values = new long[28];
        if (index >= 0) {
            values[index] = value;
        }
        Class<?>[] paramTypes = new Class<?>[28];
        java.util.Arrays.fill(paramTypes, long.class);
        Constructor<?> countsCtor = countsClass.getDeclaredConstructor(paramTypes);
        countsCtor.setAccessible(true);
        Object counts = countsCtor.newInstance(
                box(values));
        Class<?> planClass = Class.forName(
                "com.springairag.core.service.CollectionPurgeService$Plan");
        Constructor<?> planCtor = planClass.getDeclaredConstructor(
                List.class, List.class, List.class, List.class,
                List.class, List.class, countsClass);
        planCtor.setAccessible(true);
        return planCtor.newInstance(
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), counts);
    }

    private static Long[] box(long[] values) {
        Long[] boxed = new Long[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return boxed;
    }

    private void invokeValidatePreviewable(Object plan) throws Exception {
        Method method = CollectionPurgeService.class.getDeclaredMethod(
                "validatePreviewable", plan.getClass());
        method.setAccessible(true);
        try {
            method.invoke(service, plan);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
