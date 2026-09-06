package com.springairag.core.service;

import com.springairag.api.dto.CollectionPurgePreviewResponse;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;

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
                new ObjectMapper(),
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
}
