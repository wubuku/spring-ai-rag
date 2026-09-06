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
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 Collection 清理 apply 的深层分支：租约申请、集合围栏、计划
 * 漂移守卫、文档计数不一致与端到端退役链。
 *
 * <p>空计划的指纹通过反射调用服务私有 buildPlan/fingerprint 复现，
 * 保证 preview 行存储的指纹与重算值精确一致。</p>
 */
class CollectionPurgeApplyDeepTest {

    private static final UUID PREVIEW_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private JdbcTemplate jdbcTemplate;
    private RagCollectionRepository collectionRepository;
    private CollectionPurgeService service;

    @BeforeEach
    void setUp() throws Exception {
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
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        // buildPlan 的全部计数查询统一归零（空计划）。
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());
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
        request.setAttribute(
                ApiKeyAuthFilterHolder.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilterHolder.PRINCIPAL_ENVIRONMENT_ROOT);
        return request;
    }

    private String emptyPlanFingerprint() throws Exception {
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

    private CollectionPurgeApplyRequest applyRequest(String fingerprint) {
        return new CollectionPurgeApplyRequest(
                "kb", PREVIEW_ID, "token-1", fingerprint, 5L, 2L);
    }

    private void stubPreviewRow(String fingerprint) {
        String tokenHash = com.springairag.core.util.DigestUtils
                .sha256("token-1");
        java.time.Instant future = java.time.Instant.now().plusSeconds(3_600);
        when(jdbcTemplate.query(contains("FROM rag_collection_purge_preview"),
                any(org.springframework.jdbc.core.RowMapper.class), eq(PREVIEW_ID),
                eq("root:environment-root"))).thenAnswer(invocation -> {
                    org.springframework.jdbc.core.RowMapper<?> mapper =
                            invocation.getArgument(1);
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
                    when(rs.getString("status")).thenReturn("PREVIEWED");
                    when(rs.getTimestamp("preview_deadline"))
                            .thenReturn(java.sql.Timestamp.from(future));
                    when(rs.getTimestamp("operation_deadline"))
                            .thenReturn(java.sql.Timestamp.from(future));
                    when(rs.getString("result_payload")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @Test
    void applyRejectsWhenApplyLeaseIsLost() throws Exception {
        stubPreviewRow(emptyPlanFingerprint());
        // 租约申请未命中（并发 apply 抢先）。
        when(jdbcTemplate.update(contains("SET status = 'APPLYING'"),
                any(Object[].class))).thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(emptyPlanFingerprint()), request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("already being applied"));
    }

    @Test
    void applyRejectsWhenCollectionFenceMisses() throws Exception {
        stubPreviewRow(emptyPlanFingerprint());
        // 围栏写未命中：preview 之后集合被并发修改。
        when(jdbcTemplate.update(contains("SET deleted = TRUE"),
                any(Object[].class))).thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(emptyPlanFingerprint()), request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("changed after purge"));
    }

    @Test
    void applyRejectsPlanDriftWithFreshFingerprintMismatch() throws Exception {
        // preview 行存的指纹与重算值不一致 → 要求新建 preview。
        stubPreviewRow("stale-fingerprint");

        // 请求指纹与存储一致（请求校验通过），但与重算计划不一致（计划漂移）。
        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest("stale-fingerprint"), request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("create a new preview"));
    }





    /** ApiKeyAuthFilter 常量桥（避免跨包 import 噪音）。 */
    private static final class ApiKeyAuthFilterHolder {
        private static final String AUTHENTICATED_PRINCIPAL_TYPE =
                "authenticatedPrincipalType";
        private static final String PRINCIPAL_ENVIRONMENT_ROOT =
                "ENVIRONMENT_ROOT";
        private ApiKeyAuthFilterHolder() {
        }
    }
}
