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
import org.mockito.ArgumentMatchers;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集合清空完成结果读取与计划漂移二次防御长尾（Batch 615，JaCoCo
 * 驱动）：COMPLETED 预览直接回读存储结果、损坏的结果载荷拒绝、
 * requireUnchangedPlan 指纹漂移的二次防御可达性验证。
 */
class CollectionPurgeResultReadTailTest {

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
    void completedPreviewReturnsStoredResultWithoutReexecution() throws Exception {
        String fingerprint = emptyPlanFingerprint();
        String payload = """
                {"previewId":"%s","status":"COMPLETED","collectionId":10,
                 "collectionKey":"kb","purgedDocumentCount":3,
                 "purgedExternalDocumentCount":0,
                 "purgedLocalDocumentCount":0,"deletedAt":null,
                 "purgedAt":null,"collectionVersion":5}
                """.formatted(PREVIEW_ID);
        stubPreviewRow(fingerprint, "COMPLETED", payload);

        CollectionPurgeResultResponse response = service.apply(
                new CollectionPurgeApplyRequest(
                        "kb", PREVIEW_ID, "token-1", fingerprint, 5L, 2L),
                request());

        assertEquals("COMPLETED", response.status());
        assertEquals(3L, response.purgedDocumentCount());
        // 直接回读：不执行任何删除、不再推进 fence。
        verify(jdbcTemplate, times(0)).update(
                contains("deleted = TRUE"), any(Object[].class));
    }

    @Test
    void completedPreviewWithCorruptPayloadIsRejected() throws Exception {
        stubPreviewRow(emptyPlanFingerprint(), "COMPLETED", "not-json");

        RagException error = assertThrows(RagException.class,
                () -> service.apply(new CollectionPurgeApplyRequest(
                        "kb", PREVIEW_ID, "token-1",
                        emptyPlanFingerprint(), 5L, 2L), request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("result is invalid"));
    }

    @Test
    void planDriftSecondaryDefenseConflictsAfterFrozenCheck() throws Exception {
        // 冻结校验通过（请求指纹 = 预览指纹），但 apply 时实时计划
        // 已漂移（计数 0→3）→ 二次防御以 CONFLICT 拒绝。
        String fingerprint = emptyPlanFingerprint();
        stubPreviewRow(fingerprint, "PREVIEWED", null);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(3L);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(new CollectionPurgeApplyRequest(
                        "kb", PREVIEW_ID, "token-1", fingerprint, 5L, 2L),
                        request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("plan changed"));
    }
}
