package com.springairag.core.service;

import com.springairag.api.dto.CollectionPurgeApplyRequest;
import com.springairag.api.dto.CollectionPurgePreviewResponse;
import com.springairag.api.dto.CollectionPurgeResultResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 Collection 清理服务的 preview 守卫链与 scheduledCleanup：
 * 授权、key 校验、活跃预览上限、未知/已退役集合、持久化与三段清理。
 */
class CollectionPurgeServiceTest {

    private JdbcTemplate jdbcTemplate;
    private CollectionPurgeAuthorization authorization;
    private RagCollectionRepository collectionRepository;
    private CollectionPurgeService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionRepository = mock(RagCollectionRepository.class);
        authorization = mock(CollectionPurgeAuthorization.class);
        RagProperties ragProperties = new RagProperties();
        service = new CollectionPurgeService(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules(),
                collectionRepository,
                authorization,
                ragProperties,
                mock(PlatformTransactionManager.class));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
        return request;
    }

    private RagCollection collection() {
        RagCollection collection = new RagCollection();
        collection.setId(10L);
        collection.setCollectionKey("kb");
        return collection;
    }

    @Test
    void previewPropagatesAuthorizationFailure() {
        org.mockito.Mockito.doThrow(new RagException(
                        ErrorCode.COLLECTION_PURGE_CONFLICT, "not allowed"))
                .when(authorization).requireAllowed(any());

        RagException error = assertThrows(RagException.class,
                () -> service.preview("kb", request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT, error.getErrorCodeEnum());
    }

    @Test
    void previewRejectsInvalidCollectionKey() {
        assertThrows(IllegalArgumentException.class,
                () -> service.preview(" ", request()));
        assertThrows(IllegalArgumentException.class,
                () -> service.preview(null, request()));
    }

    @Test
    void previewEnforcesActivePreviewLimit() {
        // 第一次 count 是该 owner 的活跃预览数，达到上限即冲突。
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(20L);

        RagException error = assertThrows(RagException.class,
                () -> service.preview("kb", request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("Too many active"));
    }

    @Test
    void previewRejectsUnknownCollection() {
        when(collectionRepository.findByCollectionKey("ghost"))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.preview("ghost", request()));
        assertEquals(ErrorCode.COLLECTION_NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void previewRejectsAlreadyRetiredCollection() {
        RagCollection retired = collection();
        retired.setPurgedAt(java.time.LocalDateTime.now());
        when(collectionRepository.findByCollectionKey("kb"))
                .thenReturn(Optional.of(retired));

        RagException error = assertThrows(RagException.class,
                () -> service.preview("kb", request()));
        assertEquals(ErrorCode.COLLECTION_ALREADY_RETIRED,
                error.getErrorCodeEnum());
    }

    @Test
    void previewPersistsPreviewedRowAndReturnsBoundedResponse() {
        when(collectionRepository.findByCollectionKey("kb"))
                .thenReturn(Optional.of(collection()));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        CollectionPurgePreviewResponse response = service.preview("kb", request());

        assertEquals(10L, response.collectionId());
        assertEquals("kb", response.collectionKey());
        assertEquals(0L, response.documentCount());
        assertNotNull(response.previewId());
        assertNotNull(response.confirmationToken());
        assertNotNull(response.fingerprint());
        assertTrue(response.previewExpiresAt() != null);
        assertTrue(response.operationExpiresAt() != null);
        verify(jdbcTemplate).update(contains("INSERT INTO rag_collection_purge_preview"),
                any(Object[].class));
    }

    @Test
    void scheduledCleanupRunsAllThreeMaintenanceStatements() {
        service.scheduledCleanup();

        verify(jdbcTemplate, times(3)).update(anyString(), any(Object[].class));
        verify(jdbcTemplate).update(contains("apply_lease_expires_at <"),
                any(Object[].class));
        verify(jdbcTemplate).update(contains("operation_deadline <="),
                any(Object[].class));
        verify(jdbcTemplate).update(contains("DELETE FROM rag_collection_purge_preview"),
                any(Object[].class));
    }


    // ── apply 事务流（Batch 97）───────────────────────────────────────

    private static final java.util.UUID PREVIEW_ID =
            java.util.UUID.fromString("11111111-1111-1111-1111-111111111111");

    private CollectionPurgeApplyRequest applyRequest() {
        return new CollectionPurgeApplyRequest(
                "kb", PREVIEW_ID, "token-1", "fp-1", 5L, 2L);
    }

    private void stubPreviewRow(String status, String resultPayload) {
        String tokenHash = com.springairag.core.util.DigestUtils
                .sha256("token-1");
        java.time.Instant future =
                java.time.Instant.now().plusSeconds(3_600);
        when(jdbcTemplate.query(contains("FROM rag_collection_purge_preview"),
                any(RowMapper.class), eq(PREVIEW_ID),
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
                    when(rs.getString("fingerprint")).thenReturn("fp-1");
                    when(rs.getString("status")).thenReturn(status);
                    when(rs.getTimestamp("preview_deadline"))
                            .thenReturn(java.sql.Timestamp.from(future));
                    when(rs.getTimestamp("operation_deadline"))
                            .thenReturn(java.sql.Timestamp.from(future));
                    when(rs.getString("result_payload")).thenReturn(resultPayload);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @Test
    void applyPropagatesAuthorizationFailure() {
        org.mockito.Mockito.doThrow(new RagException(
                        ErrorCode.COLLECTION_PURGE_CONFLICT, "not allowed"))
                .when(authorization).requireAllowed(any());

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(), request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT, error.getErrorCodeEnum());
    }

    @Test
    void applyRejectsNullRequestBody() {
        assertThrows(NullPointerException.class,
                () -> service.apply(null, request()));
    }

    @Test
    void applyRejectsInvalidCollectionKey() {
        assertThrows(IllegalArgumentException.class,
                () -> service.apply(new CollectionPurgeApplyRequest(
                        " ", PREVIEW_ID, "t", "f", 5L, 2L), request()));
    }

    @Test
    void applyRejectsMissingPreviewAsExpired() {
        when(jdbcTemplate.query(contains("FROM rag_collection_purge_preview"),
                any(RowMapper.class), eq(PREVIEW_ID),
                eq("root:environment-root"))).thenReturn(List.of());

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(), request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_PREVIEW_EXPIRED,
                error.getErrorCodeEnum());
    }

    @Test
    void applyRejectsFrozenRequestMismatch() {
        stubPreviewRow("PREVIEWED", null);
        CollectionPurgeApplyRequest stale =
                new CollectionPurgeApplyRequest(
                        "kb", PREVIEW_ID, "token-1", "fp-1", 4L, 2L);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(stale, request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("does not match"));
    }

    @Test
    void applyRejectsInvalidConfirmationToken() {
        stubPreviewRow("PREVIEWED", null);
        CollectionPurgeApplyRequest wrongToken =
                new CollectionPurgeApplyRequest(
                        "kb", PREVIEW_ID, "wrong-token", "fp-1", 5L, 2L);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(wrongToken, request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFIRMATION_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void applyReturnsCachedResultForCompletedPreview() throws Exception {
        CollectionPurgeResultResponse cached =
                new CollectionPurgeResultResponse(
                        PREVIEW_ID, "COMPLETED", 10L, "kb", 7, 3, 7,
                        java.time.LocalDateTime.parse("2026-09-06T10:00:00"),
                        java.time.LocalDateTime.parse("2026-09-06T10:00:00"), 6);
        String payload = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(cached);
        stubPreviewRow("COMPLETED", payload);

        CollectionPurgeResultResponse result =
                service.apply(applyRequest(), request());

        assertEquals("COMPLETED", result.status());
        assertEquals(7, result.purgedDocumentCount());
        assertEquals(6, result.collectionVersion());
        // COMPLETED 幂等回放不应触发租约申请或围栏写。
        verify(jdbcTemplate, org.mockito.Mockito.never())
                .update(contains("SET status = 'APPLYING'"), any(Object[].class));
        verify(jdbcTemplate, org.mockito.Mockito.never())
                .update(contains("SET deleted = TRUE"), any(Object[].class));
    }

}

