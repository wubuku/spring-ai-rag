package com.springairag.core.service;

import com.springairag.api.dto.CollectionPurgeApplyRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
import static org.mockito.Mockito.when;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 集合清空申请长尾（Batch 608，JaCoCo 驱动）：preview 过期、
 * fence CAS 未命中（集合被并发修改）、plan 指纹漂移三类 apply
 * 拒绝路径。
 */
class CollectionPurgeApplyExpiryTailTest {

    private static final UUID PREVIEW_ID = UUID.fromString(
            "00000000-0000-0000-0000-00000000abcd");

    private JdbcTemplate jdbcTemplate;
    private RagCollectionRepository collectionRepository;
    private RagProperties ragProperties;
    private CollectionPurgeService service;

    @BeforeEach
    void setUp() {
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
        request.setAttribute(
                "authenticatedPrincipalType", "ENVIRONMENT_ROOT");
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

    private CollectionPurgeApplyRequest applyRequest(String fingerprint) {
        return new CollectionPurgeApplyRequest(
                "kb", PREVIEW_ID, "token-1", fingerprint, 5L, 2L);
    }

    private void stubPreviewRow(String fingerprint, boolean expired) {
        String tokenHash = com.springairag.core.util.DigestUtils
                .sha256("token-1");
        Instant future = Instant.now().plusSeconds(3_600);
        Instant past = Instant.now().minusSeconds(3_600);
        when(jdbcTemplate.query(contains("FROM rag_collection_purge_preview"),
                any(RowMapper.class), eq(PREVIEW_ID), eq("root:environment-root")))
                .thenAnswer(invocation -> {
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
                    when(rs.getString("status")).thenReturn("PREVIEWED");
                    when(rs.getTimestamp("preview_deadline")).thenReturn(
                            java.sql.Timestamp.from(expired ? past : future));
                    when(rs.getTimestamp("operation_deadline")).thenReturn(
                            java.sql.Timestamp.from(expired ? past : future));
                    when(rs.getString("result_payload")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @Test
    void applyRejectsExpiredPreview() throws Exception {
        stubPreviewRow(emptyPlanFingerprint(), true);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(emptyPlanFingerprint()),
                        request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_PREVIEW_EXPIRED,
                error.getErrorCodeEnum());
    }

    @Test
    void applyRejectsWhenCollectionFencedConcurrently() throws Exception {
        stubPreviewRow(emptyPlanFingerprint(), false);
        when(jdbcTemplate.update(contains("chat_commit_fence_version = ?"),
                any(Object[].class))).thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(emptyPlanFingerprint()),
                        request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("changed after purge preview"));
    }

    @Test
    void applyRejectsWhenPlanFingerprintDrifted() throws Exception {
        stubPreviewRow(emptyPlanFingerprint(), false);
        // apply 时计划出现非零计数 → 当前指纹与预览不一致。
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(3L);

        // 请求指纹与实时计划不一致 → 在请求冻结校验层即拒绝。
        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(emptyPlanFingerprint()),
                        request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFIRMATION_INVALID,
                error.getErrorCodeEnum());
    }
}
