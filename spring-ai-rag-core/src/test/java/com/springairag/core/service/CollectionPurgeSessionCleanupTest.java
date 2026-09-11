package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionPurgeApplyRequest;
import com.springairag.api.dto.CollectionPurgeResultResponse;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.service.CollectionPurgeAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Collection 清理的会话清理分支（deleteSessions）：匿名会话走
 * owner IS NULL 的历史 + spring_ai 记忆删除；归属会话追加摘要/
 * 回合操作/租约清理，并按 memoryConversationId 清理共享记忆。
 */
class CollectionPurgeSessionCleanupTest {

    private static final UUID PREVIEW_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

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

        // 计数类查询统一归零（含 previewable 校验的活跃工作检查）。
        Mockito.lenient().when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        // 全部 UPDATE/DELETE 默认命中 1 行。
        Mockito.lenient().when(jdbcTemplate.update(
                anyString(), any(Object[].class))).thenReturn(1);
        Mockito.lenient().when(jdbcTemplate.update(anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);

        // 计划：集合内 1 个文档。
        Mockito.lenient().when(jdbcTemplate.query(
                contains("SELECT id FROM rag_documents WHERE collection_id"),
                any(RowMapper.class), any()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong(1)).thenReturn(1L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        // 会话：匿名（owner NULL）与归属（db:1）各一个。
        Mockito.lenient().when(jdbcTemplate.query(
                contains("FROM rag_chat_history history"),
                any(RowMapper.class), any()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet anonymous = mock(ResultSet.class);
                    when(anonymous.getString("owner_principal_id"))
                            .thenReturn(null);
                    when(anonymous.getString("session_id"))
                            .thenReturn("anon-session");
                    ResultSet owned = mock(ResultSet.class);
                    when(owned.getString("owner_principal_id"))
                            .thenReturn("db:key-1");
                    when(owned.getString("session_id"))
                            .thenReturn("user-session");
                    return List.of(
                            mapper.mapRow(anonymous, 0),
                            mapper.mapRow(owned, 1));
                });
        // 退役后最终状态查询（buildRetiredResult）。
        Mockito.lenient().when(jdbcTemplate.queryForObject(
                contains("SELECT deleted_at, purged_at, version"),
                any(RowMapper.class), any()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    java.time.LocalDateTime now = java.time.LocalDateTime.now();
                    when(rs.getTimestamp("deleted_at"))
                            .thenReturn(java.sql.Timestamp.valueOf(now));
                    when(rs.getTimestamp("purged_at"))
                            .thenReturn(java.sql.Timestamp.valueOf(now));
                    when(rs.getLong("version")).thenReturn(6L);
                    return mapper.mapRow(rs, 0);
                });
        // 其余计划查询为空。
        Mockito.lenient().when(jdbcTemplate.query(
                contains("rag_user_feedback_document"), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        Mockito.lenient().when(jdbcTemplate.query(
                contains("entity_type = 'Document'"), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        Mockito.lenient().when(jdbcTemplate.query(
                contains("entity_type = 'Collection'"), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        Mockito.lenient().when(jdbcTemplate.query(
                contains("rag_derivation_repair_previews"), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        Mockito.lenient().when(jdbcTemplate.query(
                contains("rag_document_idempotency_operations"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        RagCollection collection = new RagCollection();
        collection.setId(10L);
        collection.setCollectionKey("kb");
        when(collectionRepository.findByCollectionKey("kb"))
                .thenReturn(Optional.of(collection));
        when(collectionRepository.findById(10L))
                .thenReturn(Optional.of(collection));
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_PRINCIPAL_TYPE,
                com.springairag.core.filter.ApiKeyAuthFilter
                        .PRINCIPAL_ENVIRONMENT_ROOT);
        return request;
    }

    /** 经反射复算非空计划指纹（buildPlan 读取上方 JDBC 桩数据）。 */
    private String planFingerprint() throws Exception {
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

    @Test
    void applyPurgesAnonymousAndOwnedSessionArtifacts() throws Exception {
        String fingerprint = planFingerprint();
        stubPreviewRow(fingerprint);
        CollectionPurgeApplyRequest requestBody = new CollectionPurgeApplyRequest(
                "kb", PREVIEW_ID, "token-1", fingerprint, 5L, 2L);

        CollectionPurgeResultResponse result =
                service.apply(requestBody, request());

        assertNotNull(result);
        // 匿名会话：owner IS NULL 的历史删除。
        verify(jdbcTemplate).update(
                contains("owner_principal_id IS NULL AND session_id = ?"),
                eq("anon-session"));
        // 归属会话：历史/摘要/回合操作/过期租约各表各清理一次。
        verify(jdbcTemplate).update(
                contains(
                        "DELETE FROM rag_chat_history "
                                + "WHERE owner_principal_id = ?"),
                eq("db:key-1"), eq("user-session"));
        verify(jdbcTemplate, times(1)).update(
                contains("rag_chat_memory_summary"), any(Object[].class));
        verify(jdbcTemplate, times(1)).update(
                contains("rag_chat_turn_operations"), any(Object[].class));
        verify(jdbcTemplate, times(1)).update(
                contains("rag_chat_session_lease"), any(Object[].class));
        // spring_ai 记忆清理：匿名按 session、归属按 memoryConversationId。
        verify(jdbcTemplate, times(2)).update(
                contains("DELETE FROM spring_ai_chat_memory"),
                any(Object[].class));
    }

    private void stubPreviewRow(String fingerprint) {
        String tokenHash = com.springairag.core.util.DigestUtils
                .sha256("token-1");
        java.time.Instant future = java.time.Instant.now().plusSeconds(3_600);
        when(jdbcTemplate.query(contains("FROM rag_collection_purge_preview"),
                any(RowMapper.class), eq(PREVIEW_ID),
                eq("root:environment-root"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
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

}
