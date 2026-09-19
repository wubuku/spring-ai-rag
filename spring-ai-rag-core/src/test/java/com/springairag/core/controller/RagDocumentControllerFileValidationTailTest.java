package com.springairag.core.controller;

import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * RagDocumentController 文件校验与集合解析长尾（Batch 538，JaCoCo
 * 驱动）：validateTextFile 的扩展名/内容类型矩阵、readFileContent
 * 空内容与标题去后缀、auditDelete 双重载透传、resolveOptional
 * CollectionId 双空短路、normalizeDocumentCollectionScopes 空列表
 * 短路。
 */
class RagDocumentControllerFileValidationTailTest {

    private RagDocumentController controller;
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = mock(AuditLogService.class);
        controller = new RagDocumentController(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                mock(DocumentEmbedService.class),
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                auditLogService);
    }

    /** 私有 record 的访问器统一反射读取。 */
    private static Object field(Object record, String accessor)
            throws Exception {
        return record.getClass().getMethod(accessor).invoke(record);
    }

    private Object invoke(String name, Class<?>[] params, Object... args)
            throws Exception {
        Method method = RagDocumentController.class
                .getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method.invoke(controller, args);
    }

    private MockMultipartFile file(String name, String contentType,
                                   byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateTextFileAcceptsTextTypesAndMarkdownExtension()
            throws Exception {
        var params = new Class<?>[]{MultipartFile.class, String.class};

        var byExtension = invoke("validateTextFile", params,
                file("notes.md", "application/octet-stream",
                        "hello".getBytes()), "notes.md");
        assertEquals(Boolean.TRUE, field(byExtension, "isText"));
        assertEquals("md", field(byExtension, "extension"));

        var byContentType = invoke("validateTextFile", params,
                file("data.bin", "text/plain", "hello".getBytes()),
                "data.bin");
        assertEquals(Boolean.TRUE, field(byContentType, "isText"));

        var noDot = invoke("validateTextFile", params,
                file("README", "application/octet-stream",
                        "hello".getBytes()), "README");
        assertEquals("", field(noDot, "extension"));
        assertEquals(Boolean.FALSE, field(noDot, "isText"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readFileContentStripsTitleSuffixAndRejectsBlank() throws Exception {
        var params = new Class<?>[]{MultipartFile.class, String.class};

        var ok = invoke("readFileContent", params,
                file("notes.md", "text/plain", "正文".getBytes()),
                "notes.md");
        assertEquals("notes", field(ok, "title"));
        assertEquals("正文", field(ok, "content"));

        var blank = invoke("readFileContent", params,
                file("empty.md", "text/plain", "  ".getBytes()),
                "empty.md");
        assertNull(field(blank, "title"));
        assertEquals("File content is empty", field(blank, "errorMessage"));
    }

    @Test
    void auditDeleteDelegatesToAuditService() throws Exception {
        invoke("auditDelete",
                new Class<?>[]{String.class, String.class, String.class},
                "Document", "7", "removed");
        invoke("auditDelete",
                new Class<?>[]{String.class, String.class, String.class,
                        Map.class},
                "Document", "8", "removed", Map.of("k", "v"));

        verify(auditLogService).logDelete("Document", "7", "removed");
        verify(auditLogService).logDelete(
                "Document", "8", "removed", Map.of("k", "v"));
    }

    @Test
    void resolveOptionalCollectionIdShortCircuitsWhenBothAbsent()
            throws Exception {
        Object result = invoke("resolveOptionalCollectionId",
                new Class<?>[]{Long.class, String.class,
                        com.springairag.core.security.ApiAccessPolicy.class},
                null, null, null);
        assertNull(result);
    }

    @Test
    void normalizeDocumentCollectionScopesToleratesNullList() throws Exception {
        invoke("normalizeDocumentCollectionScopes",
                new Class<?>[]{List.class, Long.class,
                        com.springairag.core.security.ApiAccessPolicy.class},
                null, 1L, null);
    }
}
