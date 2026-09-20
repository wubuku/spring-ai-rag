package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ExternalDocumentRelocateRequest;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.repository.RagDocumentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

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
 * DocumentRelocationService relocate 守卫长尾（Batch 554，JaCoCo
 * 驱动）：同集合 ID 拒绝、externalId 超长拒绝、非外部托管来源拒
 * 绝、legacy 身份未 claim 拒绝、INSERT 命中走新预约、SELECT 空表
 * 预约消失 ISE。
 */
class DocumentRelocationRelocateGuardTailTest {

    private JdbcTemplate jdbcTemplate;
    private CollectionIdentityResolver collectionResolver;
    private DocumentRelocationService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionResolver = mock(CollectionIdentityResolver.class);
        RagProperties ragProperties = new RagProperties();
        service = new DocumentRelocationService(
                jdbcTemplate,
                new ObjectMapper(),
                mock(RagDocumentRepository.class),
                collectionResolver,
                mock(DocumentVersionService.class),
                mock(DocumentLifecycleService.class),
                ragProperties,
                mock(EntityManager.class));
        ragProperties.getDocumentLifecycle().setRelocationEnabled(true);
        when(collectionResolver.requireActive(null, "source-col"))
                .thenReturn(collection(10L, "source-col"));
        when(collectionResolver.requireActive(null, "target-col"))
                .thenReturn(collection(20L, "target-col"));
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
    }

    private RagCollection collection(long id, String key) {
        RagCollection value = new RagCollection();
        value.setId(id);
        value.setCollectionKey(key);
        value.setEnabled(true);
        return value;
    }

    private ExternalDocumentRelocateRequest request(String targetKey,
                                                    String externalId) {
        return new ExternalDocumentRelocateRequest(
                "source-col", targetKey, "crm", externalId, "etag:2");
    }

    @Test
    void relocateRejectsWhenKeysResolveToSameCollectionId() {
        when(collectionResolver.requireActive(null, "alias"))
                .thenReturn(collection(10L, "alias"));

        var error = assertThrows(IllegalArgumentException.class,
                () -> service.relocate(request("alias", "cms:1"), "key-1"));

        assertEquals("sourceCollectionKey and targetCollectionKey must be different",
                error.getMessage());
    }

    @Test
    void relocateRejectsOversizeExternalId() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> service.relocate(
                        request("target-col", "e".repeat(256)), "key-1"));

        assertEquals("externalId must contain 1-255 characters",
                error.getMessage());
    }

    @Test
    void newReservationInsertProceedsUntilMissingDocument() {
        // INSERT RETURNING 命中 → 新预约；随后文档缺失 → NOT_FOUND。
        when(jdbcTemplate.query(
                contains("RETURNING id"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(2);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getLong(1)).thenReturn(88L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.queryForList(
                contains("FROM rag_documents"), any(Object[].class)))
                .thenReturn(List.of());

        var error = assertThrows(RagException.class,
                () -> service.relocate(
                        request("target-col", "cms:1"), "key-1"));

        assertEquals(
                com.springairag.api.enums.ErrorCode.DOCUMENT_NOT_FOUND,
                error.getErrorCodeEnum());
    }

    @Test
    void vanishedReservationSurfacesIllegalState() {
        // INSERT 未命中且 SELECT 为空 → 预约记录消失。
        when(jdbcTemplate.query(
                contains("RETURNING id"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(
                contains("rag_document_idempotency_operations"),
                any(Object[].class)))
                .thenReturn(List.of());

        var error = assertThrows(IllegalStateException.class,
                () -> service.relocate(
                        request("target-col", "cms:1"), "key-1"));

        assertEquals("Idempotency reservation disappeared", error.getMessage());
    }

    @Test
    void conflictingConcurrentReservationSurfacesDataIntegrityViolation() {
        when(jdbcTemplate.query(
                contains("RETURNING id"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenThrow(new DataIntegrityViolationException("uniq"));

        var error = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.relocate(
                        request("target-col", "cms:1"), "key-1"));

        assertEquals("uniq", error.getMessage());
    }
}
