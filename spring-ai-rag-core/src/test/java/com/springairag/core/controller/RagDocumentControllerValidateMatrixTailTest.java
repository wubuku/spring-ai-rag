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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagDocumentController 校验矩阵长尾（Batch 637，JaCoCo 驱动）：
 * validateTextFile 的内容类型变体（json/xml/javascript、null 类型
 * +文本扩展名矩阵）、非文本可读文件无错误返回、非文本不可读文件
 * 的 Unsupported 报告、空文件跳过字节读取；readFileContent 读取
 * 失败投影；parseDateParam 四臂；auditCreate 双重载。
 */
class RagDocumentControllerValidateMatrixTailTest {

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

    private Object invoke(String name, Class<?>[] params, Object... args)
            throws Exception {
        Method method = RagDocumentController.class
                .getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method.invoke(controller, args);
    }

    private static Object field(Object record, String accessor)
            throws Exception {
        return record.getClass().getMethod(accessor).invoke(record);
    }

    private MockMultipartFile file(String name, String contentType,
                                   byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    private Object validate(MultipartFile multipart, String filename)
            throws Exception {
        return invoke("validateTextFile",
                new Class<?>[]{MultipartFile.class, String.class},
                multipart, filename);
    }

    @Test
    void structuredTextContentTypesAreAccepted() throws Exception {
        for (String contentType : new String[]{
                "application/json", "application/xml",
                "application/javascript"}) {
            Object result = validate(
                    file("data." + contentType.substring(12),
                            contentType, "{}".getBytes()),
                    "data." + contentType.substring(12));
            assertEquals(Boolean.TRUE, field(result, "isText"),
                    () -> "应接受 " + contentType);
        }
    }

    @Test
    void nullContentTypeIsAlwaysNonTextRegardlessOfExtension()
            throws Exception {
        // 扩展名判定嵌套在 contentType != null 守卫内：
        // 类型缺失时 isText 恒为 false（可读时无错误信息）。
        for (String extension : new String[]{
                "txt", "md", "markdown", "json", "xml", "html", "csv",
                "log"}) {
            Object result = validate(
                    file("file." + extension, null, "x".getBytes()),
                    "file." + extension);
            assertEquals(Boolean.FALSE, field(result, "isText"),
                    () -> "null 类型时 ." + extension + " 应为非文本");
            assertNull(field(result, "errorMessage"));
        }
    }

    @Test
    void nullOriginalFilenameYieldsEmptyExtensionAndNonText()
            throws Exception {
        MultipartFile multipart = mock(MultipartFile.class);
        when(multipart.getOriginalFilename()).thenReturn(null);
        when(multipart.getContentType()).thenReturn(null);
        when(multipart.isEmpty()).thenReturn(false);
        when(multipart.getBytes()).thenReturn("x".getBytes());

        Object result = validate(multipart, "any");

        assertEquals("", field(result, "extension"));
        assertEquals(Boolean.FALSE, field(result, "isText"));
        assertNull(field(result, "errorMessage"));
    }

    @Test
    void unreadableNonTextFileReportsUnsupportedType() throws Exception {
        MultipartFile multipart = mock(MultipartFile.class);
        when(multipart.getOriginalFilename()).thenReturn("blob.bin");
        when(multipart.getContentType()).thenReturn("application/octet-stream");
        when(multipart.isEmpty()).thenReturn(false);
        when(multipart.getBytes())
                .thenThrow(new java.io.IOException("无法读取二进制"));

        Object result = validate(multipart, "blob.bin");

        assertEquals(Boolean.FALSE, field(result, "isText"));
        String message = (String) field(result, "errorMessage");
        assertTrue(message.contains("Unsupported file type"));
        assertTrue(message.contains("only text files supported"));
    }

    @Test
    void emptyNonTextFileSkipsByteRead() throws Exception {
        MultipartFile multipart = mock(MultipartFile.class);
        when(multipart.getOriginalFilename()).thenReturn("blob.bin");
        when(multipart.getContentType()).thenReturn("application/octet-stream");
        when(multipart.isEmpty()).thenReturn(true);

        Object result = validate(multipart, "blob.bin");

        assertEquals(Boolean.FALSE, field(result, "isText"));
        assertNull(field(result, "errorMessage"));
    }

    @Test
    void readFileContentReadFailureIsReported() throws Exception {
        MultipartFile multipart = mock(MultipartFile.class);
        when(multipart.getBytes())
                .thenThrow(new java.io.IOException("磁盘故障"));

        Object result = invoke("readFileContent",
                new Class<?>[]{MultipartFile.class, String.class},
                multipart, "notes.md");

        assertNull(field(result, "title"));
        String message = (String) field(result, "errorMessage");
        assertTrue(message.startsWith("Failed to read file:"));
    }

    @Test
    void parseDateParamCoversAllArms() throws Exception {
        assertNull(invoke("parseDateParam",
                new Class<?>[]{String.class}, (Object) null));
        assertNull(invoke("parseDateParam",
                new Class<?>[]{String.class}, "   "));
        Object parsed = invoke("parseDateParam",
                new Class<?>[]{String.class}, "2026-01-02T10:15:00");
        assertEquals("2026-01-02T10:15", parsed.toString());
        assertNull(invoke("parseDateParam",
                new Class<?>[]{String.class}, "not-a-date"));
    }

    @Test
    void auditCreateDelegatesToAuditService() throws Exception {
        invoke("auditCreate",
                new Class<?>[]{String.class, String.class, String.class},
                "Document", "9", "created");
        invoke("auditCreate",
                new Class<?>[]{String.class, String.class, String.class,
                        java.util.Map.class},
                "Document", "10", "created", java.util.Map.of("k", "v"));

        verify(auditLogService).logCreate("Document", "9", "created");
        verify(auditLogService).logCreate(
                "Document", "10", "created", java.util.Map.of("k", "v"));
    }
}
