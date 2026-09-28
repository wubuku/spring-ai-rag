package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentVersionService 版本号分配长尾（Batch 696，JaCoCo 驱
 * 动）：便捷构造器（无 JdbcTemplate）回退到仓储查询分配、
 * JdbcTemplate 的 UPDATE RETURNING 分配与 null 防御。
 */
class DocumentVersionServiceAllocationTailTest {

    private RagDocumentVersionRepository versionRepository;
    private RagDocument sampleDoc;

    @BeforeEach
    void setUp() {
        versionRepository = mock(RagDocumentVersionRepository.class);
        sampleDoc = new RagDocument();
        sampleDoc.setId(1L);
        sampleDoc.setTitle("测试文档");
        sampleDoc.setContent("这是测试内容");
        sampleDoc.setContentHash("hash-696");
        sampleDoc.setSize(100L);
        sampleDoc.setMetadata(Map.of("key", "value"));
        when(versionRepository.save(any(RagDocumentVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void convenienceConstructorAllocatesFromRepositoryHistory() {
        when(versionRepository.findLatestByDocumentId(1L))
                .thenReturn(Optional.empty());
        var service = new DocumentVersionService(versionRepository);

        RagDocumentVersion first =
                service.forceRecordVersion(sampleDoc, "CREATE", "首次");

        assertEquals(1, first.getVersionNumber());

        RagDocumentVersion latest = new RagDocumentVersion();
        latest.setVersionNumber(4);
        when(versionRepository.findLatestByDocumentId(1L))
                .thenReturn(Optional.of(latest));

        RagDocumentVersion fifth =
                service.forceRecordVersion(sampleDoc, "UPDATE", "修订");

        assertEquals(5, fifth.getVersionNumber());
    }

    @Test
    void jdbcTemplateAllocationReturnsDatabaseValue() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq(1L))).thenReturn(7);
        var service = new DocumentVersionService(
                versionRepository, jdbcTemplate);

        RagDocumentVersion version =
                service.forceRecordVersion(sampleDoc, "UPDATE", "修订");

        assertEquals(7, version.getVersionNumber());
        assertNotNull(version.getContentHash());
    }

    @Test
    void jdbcTemplateAllocationNullThrowsIllegalState() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq(1L))).thenReturn(null);
        var service = new DocumentVersionService(
                versionRepository, jdbcTemplate);

        assertThrows(IllegalStateException.class,
                () -> service.forceRecordVersion(sampleDoc, "UPDATE", "修订"));
    }
}
