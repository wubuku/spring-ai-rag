package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagCollectionPurgeProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 集合清空 preview 校验长尾（Batch 623，JaCoCo 驱动）：
 * validatePreviewable 对未完成内容索引、活跃同步/修复/会话与超出
 * 同步限制的逐项冲突，以及活跃 preview 数量上限冲突。
 */
class CollectionPurgePreviewValidationTailTest {

    private JdbcTemplate jdbcTemplate;
    private RagCollectionRepository collectionRepository;
    private RagCollectionPurgeProperties purgeProperties;
    private RagProperties ragProperties;
    private CollectionPurgeService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionRepository = mock(RagCollectionRepository.class);
        ragProperties = new RagProperties();
        purgeProperties = ragProperties.getCollectionPurge();
        purgeProperties.setMaxActivePreviewsPerOwner(1);
        service = new CollectionPurgeService(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules(),
                collectionRepository,
                mock(CollectionPurgeAuthorization.class),
                ragProperties,
                mock(PlatformTransactionManager.class));
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        // 默认所有计数为 0（空计划，可通过具体 SQL 片段覆盖）。
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(anyString(),
                org.mockito.ArgumentMatchers.any(
                        org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class)))
                .thenReturn(null);

        RagCollection collection = new RagCollection();
        collection.setId(10L);
        collection.setCollectionKey("kb");
        when(collectionRepository.findByCollectionKey("kb"))
                .thenReturn(java.util.Optional.of(collection));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticatedPrincipalType",
                "ENVIRONMENT_ROOT");
        return request;
    }

    private void authenticateAsRoot() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticatedPrincipalType",
                "ENVIRONMENT_ROOT");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    private void stubOneDocument() {
        // documents 非空（计数 1）→ 触发 documentCount 上限分支。
        when(jdbcTemplate.query(
                contains("SELECT id FROM rag_documents"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(),
                eq(10L)))
                .thenReturn(List.of(1L));
    }

    @Test
    void previewRejectsIncompleteContentReferenceIndexes() {
        authenticateAsRoot();
        // 未完成的 chat 内容索引计数 → 1（默认 SQL 片段即可命中）。
        when(jdbcTemplate.queryForObject(
                contains("content_reference_index_complete = FALSE"),
                eq(Long.class), any(Object[].class))).thenReturn(1L);

        RagException error = assertThrows(RagException.class,
                () -> service.preview("kb", request()));
        assertEquals(ErrorCode.COLLECTION_PURGE_CONFLICT,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage()
                .contains("Content reference indexes are incomplete"));
    }

    @Test
    void previewRejectsCollectionWithActiveWork() {
        authenticateAsRoot();
        stubOneDocument();
        // 活跃同步运行 → 1。
        when(jdbcTemplate.queryForObject(
                contains("rag_document_sync_runs"), eq(Long.class),
                any(Object[].class))).thenReturn(1L);

        RagException error = assertThrows(RagException.class,
                () -> service.preview("kb", request()));
        assertTrue(error.getMessage().contains("active work or Chat sessions"));
    }

    @Test
    void previewRejectsWhenSynchronousLimitsExceeded() {
        authenticateAsRoot();
        ragProperties.getCollectionPurge().setMaxDocuments(0);
        stubOneDocument();

        RagException error = assertThrows(RagException.class,
                () -> service.preview("kb", request()));
        assertTrue(error.getMessage()
                .contains("exceeds configured synchronous limits"));
    }

    @Test
    void previewRejectsTooManyActivePreviewsForOwner() {
        authenticateAsRoot();
        // owner 已有 1 个活跃 preview（默认上限 1）→ 冲突。
        when(jdbcTemplate.queryForObject(
                contains("rag_collection_purge_preview"), eq(Long.class),
                any(Object[].class))).thenReturn(1L);

        RagException error = assertThrows(RagException.class,
                () -> service.preview("kb", request()));
        assertTrue(error.getMessage().contains("Too many active Collection"));
    }
}
