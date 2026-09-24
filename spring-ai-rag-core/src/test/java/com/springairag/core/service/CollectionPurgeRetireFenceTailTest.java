package com.springairag.core.service;

import com.springairag.api.dto.CollectionPurgeApplyRequest;
import com.springairag.api.dto.CollectionPurgeResultResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
 * 集合清空退役 CAS 与文档计数 fencing 长尾（Batch 616，JaCoCo
 * 驱动）：deletePurgeTargets 的删除数与计划计数不一致冲突、
 * markCollectionRetired 的退役 CAS 未命中冲突、全流成功路径的
 * COMPLETED 结果投影。
 */
class CollectionPurgeRetireFenceTailTest {

    private static final UUID PREVIEW_ID = UUID.fromString(
            "00000000-0000-0000-0000-00000000abcd");

    private JdbcTemplate jdbcTemplate;
    private RagCollectionRepository collectionRepository;
    private CollectionPurgeService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionRepository = mock(RagCollectionRepository.class);
        RagProperties ragProperties = new RagProperties();
        service = new CollectionPurgeService(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules(),
                collectionRepository,
                mock(CollectionPurgeAuthorization.class),
                ragProperties,
                mock(PlatformTransactionManager.class));
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());
        RagCollection collection = new RagCollection();
        collection.setId(10L);
        collection.setCollectionKey("kb");
        when(collectionRepository.findByCollectionKey("kb"))
                .thenReturn(java.util.Optional.of(collection));
        when(collectionRepository.findById(10L))
                .thenReturn(java.util.Optional.of(collection));
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticatedPrincipalType",
                "ENVIRONMENT_ROOT");
        return request;
    }

    private String emptyPlanFingerprint() throws Exception {
        RagCollection collection = new RagCollection();
        collection.setId(10L);
        collection.setCollectionKey("kb");
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
        when(jdbcTemplate.query(contains("FROM rag_collection_purge_preview"),
                ArgumentMatchers.<RowMapper<?>>any(), eq(PREVIEW_ID),
                eq("root:environment-root"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("id", java.util.UUID.class))
                            .thenReturn(PREVIEW_ID);
                    when(rs.getString("owner_principal_id"))
                            .thenReturn("root:environment-root");
                    when(rs.getLong("collection_id")).thenReturn(10L);
                    when(rs.getString("collection_key")).thenReturn("kb");
                    when(rs.getLong("collection_version")).thenReturn(5L);
                    when(rs.getLong("chat_commit_fence_version")).thenReturn(2L);
                    when(rs.getString("confirmation_token_hash"))
                            .thenReturn(tokenHash);
                    when(rs.getString("fingerprint")).thenReturn(fingerprint);
                    when(rs.getString("status")).thenReturn(status);
                    when(rs.getTimestamp("preview_deadline")).thenReturn(
                            java.sql.Timestamp.from(future));
                    when(rs.getTimestamp("operation_deadline")).thenReturn(
                            java.sql.Timestamp.from(future));
                    when(rs.getString("result_payload")).thenReturn(resultPayload);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @Test
    void applyConflictsWhenDeletedDocumentsDivergeFromPlan() throws Exception {
        stubPreviewRow(emptyPlanFingerprint(), "PREVIEWED", null);
        // 删除数 2 ≠ 计划计数 0 → 文档计数 fencing 冲突。
        when(jdbcTemplate.update(
                contains("DELETE FROM rag_documents WHERE collection_id = ?"),
                eq(10L))).thenReturn(2);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(new CollectionPurgeApplyRequest(
                        "kb", PREVIEW_ID, "token-1",
                        emptyPlanFingerprint(), 5L, 2L), request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("documents changed during purge"));
    }

    @Test
    void applyConflictsWhenRetirementFenceIsLost() throws Exception {
        stubPreviewRow(emptyPlanFingerprint(), "PREVIEWED", null);
        // 删除数与计划一致（0 = 0）→ 走到退役；退役 CAS 未命中。
        // DELETE 计划计数 0，删除返回 0 → 一致，走到退役。
        when(jdbcTemplate.update(
                contains("DELETE FROM rag_documents WHERE collection_id = ?"),
                eq(10L))).thenReturn(0);
        // 退役 SQL 独有片段：purged_at = CURRENT_TIMESTAMP；CAS 未命中 → 0。
        when(jdbcTemplate.update(
                contains("purged_at = CURRENT_TIMESTAMP"),
                any(Object[].class))).thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(new CollectionPurgeApplyRequest(
                        "kb", PREVIEW_ID, "token-1",
                        emptyPlanFingerprint(), 5L, 2L), request()));
        System.out.println("FENCE_ACTUAL=" + error.getMessage());
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("retirement fence was lost"),
                "actual=" + error.getMessage());
    }

    @Test
    void successfulRetireProjectsCompletedResult() throws Exception {
        stubPreviewRow(emptyPlanFingerprint(), "PREVIEWED", null);
        // 空计划：DELETE FROM rag_documents 返回 0，与计划计数一致。
        when(jdbcTemplate.update(
                contains("DELETE FROM rag_documents WHERE collection_id = ?"),
                eq(10L))).thenReturn(0);
        // 退役后回查集合终态（CollectionState 为私有 record，反射构造）。
        Class<?> stateClass = Class.forName(
                "com.springairag.core.service.CollectionPurgeService"
                        + "$CollectionState");
        var stateCtor = stateClass.getDeclaredConstructor(
                java.time.LocalDateTime.class,
                java.time.LocalDateTime.class, long.class);
        stateCtor.setAccessible(true);
        Object finalState = stateCtor.newInstance(
                java.time.LocalDateTime.now(),
                java.time.LocalDateTime.now(), 6L);
        when(jdbcTemplate.queryForObject(
                contains("SELECT deleted_at, purged_at, version"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                eq(10L))).thenReturn(finalState);

        CollectionPurgeResultResponse response = service.apply(
                new CollectionPurgeApplyRequest(
                        "kb", PREVIEW_ID, "token-1",
                        emptyPlanFingerprint(), 5L, 2L), request());

        assertEquals("RETIRED", response.status());
        assertEquals("kb", response.collectionKey());
        assertEquals(0L, response.purgedDocumentCount());
        assertEquals(6L, response.collectionVersion());
    }
}
