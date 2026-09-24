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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 集合清空冻结请求校验长尾（Batch 614，JaCoCo 驱动）：
 * validateFrozenRequest 对 collectionKey/版本/fence 不一致的冲突、
 * 确认令牌与指纹错配的 CONFIRMATION_INVALID；requirePreviewApplicable
 * 对 APPLYING 状态与操作窗口过期的过期拒绝。
 */
class CollectionPurgeFrozenRequestTailTest {

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

    /** status/deadline 可调的 preview 行桩；令牌固定 token-1。 */
    private void stubPreviewRow(String fingerprint, String status,
                                boolean expiredDeadlines) {
        String tokenHash = com.springairag.core.util.DigestUtils
                .sha256("token-1");
        Instant future = Instant.now().plusSeconds(3_600);
        Instant past = Instant.now().minusSeconds(3_600);
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
                            java.sql.Timestamp.from(
                                    expiredDeadlines ? past : future));
                    when(rs.getTimestamp("operation_deadline")).thenReturn(
                            java.sql.Timestamp.from(
                                    expiredDeadlines ? past : future));
                    when(rs.getString("result_payload")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private CollectionPurgeApplyRequest applyRequest(String fingerprint) {
        return new CollectionPurgeApplyRequest(
                "kb", PREVIEW_ID, "token-1", fingerprint, 5L, 2L);
    }

    @Test
    void applyRejectsRequestWithMismatchedCollectionKey() throws Exception {
        String fingerprint = emptyPlanFingerprint();
        stubPreviewRow(fingerprint, "PREVIEWED", false);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(new CollectionPurgeApplyRequest(
                        "other-collection", PREVIEW_ID, "token-1",
                        fingerprint, 5L, 2L), request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("does not match the request"));
    }

    @Test
    void applyRejectsRequestWithStaleCollectionVersion() throws Exception {
        String fingerprint = emptyPlanFingerprint();
        stubPreviewRow(fingerprint, "PREVIEWED", false);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(new CollectionPurgeApplyRequest(
                        "kb", PREVIEW_ID, "token-1", fingerprint, 9L, 2L),
                        request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT,
                error.getErrorCodeEnum());
    }

    @Test
    void applyRejectsRequestWithWrongConfirmationToken() throws Exception {
        String fingerprint = emptyPlanFingerprint();
        stubPreviewRow(fingerprint, "PREVIEWED", false);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(new CollectionPurgeApplyRequest(
                        "kb", PREVIEW_ID, "wrong-token", fingerprint, 5L, 2L),
                        request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFIRMATION_INVALID,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("confirmation is invalid"));
    }

    @Test
    void applyRejectsRequestWithWrongFingerprint() throws Exception {
        stubPreviewRow(emptyPlanFingerprint(), "PREVIEWED", false);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(new CollectionPurgeApplyRequest(
                        "kb", PREVIEW_ID, "token-1", "drifted-fp", 5L, 2L),
                        request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFIRMATION_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void applyRejectsPreviewInApplyingState() throws Exception {
        String fingerprint = emptyPlanFingerprint();
        stubPreviewRow(fingerprint, "APPLYING", false);

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(fingerprint), request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_PREVIEW_EXPIRED,
                error.getErrorCodeEnum());
    }

    @Test
    void applyRejectsPreviewPastOperationDeadlineOnly() throws Exception {
        String fingerprint = emptyPlanFingerprint();
        stubPreviewRow(fingerprint, "PREVIEWED", false);
        // 预览窗口未过、操作窗口已过 → 仍判过期。
        Instant future = Instant.now().plusSeconds(3_600);
        Instant past = Instant.now().minusSeconds(3_600);
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
                            .thenReturn(com.springairag.core.util.DigestUtils
                                    .sha256("token-1"));
                    when(rs.getString("fingerprint")).thenReturn(fingerprint);
                    when(rs.getString("status")).thenReturn("PREVIEWED");
                    when(rs.getTimestamp("preview_deadline")).thenReturn(
                            java.sql.Timestamp.from(future));
                    when(rs.getTimestamp("operation_deadline")).thenReturn(
                            java.sql.Timestamp.from(past));
                    when(rs.getString("result_payload")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });

        RagException error = assertThrows(RagException.class,
                () -> service.apply(applyRequest(fingerprint), request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_PREVIEW_EXPIRED,
                error.getErrorCodeEnum());
    }
}
