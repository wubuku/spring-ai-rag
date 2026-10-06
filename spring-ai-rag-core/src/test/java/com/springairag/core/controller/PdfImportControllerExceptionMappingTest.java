package com.springairag.core.controller;

import com.springairag.api.dto.EmbedProgressEvent;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.PdfToRagResponse;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfImportService;
import com.springairag.core.service.PdfToRagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.springairag.core.service.CollectionIdentityResolver;

/**
 * PdfImportController 异常映射矩阵、短路分支与委托重载
 * （Batch 360）：async 端点拒绝 embed=sse、SKIP 路径三种异常
 * 映射、embeddingPolicy 非 SKIP 委托、SSE 进度回调与 done 事件、
 * triggerEmbeddingSync/Sse 的守卫与异常映射。
 */
class PdfImportControllerExceptionMappingTest {

    private PdfImportService pdfImportService;
    private PdfToRagService pdfToRagService;
    private PdfImportController controller;
    /**
     * Batch 947：证明进度回调**真的被调过**。
     *
     * <p>替代原来的 {@code Thread.sleep(500)}——那三处睡眠等的都是"SSE 任务跑完了"，
     * 等完却没有任何断言；而夹具本来就在 answer 里**同步**调用 {@code progress.accept(...)}，
     * 所以回调在方法返回前就已经触发过了，睡眠唯一的作用是把异步任务拖到用例之外。
     */
    private final java.util.concurrent.CountDownLatch progressFired =
            new java.util.concurrent.CountDownLatch(1);

    private CollectionIdentityResolver collectionIdentityResolver;

    @BeforeEach
    void setUp() {
        pdfImportService = mock(PdfImportService.class);
        pdfToRagService = mock(PdfToRagService.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        controller = new PdfImportController(
                pdfImportService, mock(MarkdownRendererService.class),
                pdfToRagService, collectionIdentityResolver);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "PDF".getBytes());
    }

    private PdfImportService.PdfImportResult importResult() {
        return new PdfImportService.PdfImportResult(
                "uuid-1", "uuid-1/default.md", 1, "test.pdf", "test.pdf");
    }

    private PdfToRagService.PdfToRagResult ragResult() {
        return new PdfToRagService.PdfToRagResult(
                42L, "Doc", true, "DONE", "ok", 9,
                "EMBEDDED", UUID.randomUUID(), UUID.randomUUID());
    }

    @SuppressWarnings("unchecked")
    private void stubStreamingImport() throws java.io.IOException {
        when(pdfImportService.importPdf(any(), any())).thenReturn(importResult());
        when(pdfToRagService.importPdfToRagWithEmbedding(
                anyString(), anyString(), any(), anyBoolean(),
                any(Consumer.class))).thenAnswer(invocation -> {
                    Consumer<EmbedProgressEvent> progress = invocation.getArgument(4);
                    // 进度回调在结果引用赋值前触发 → 文档 ID 显示 pending。
                    progress.accept(new EmbedProgressEvent(
                            "EMBEDDING", 1, 2, "halfway", null));
                    progressFired.countDown();
                    return ragResult();
                });
    }

    @Test
    void importPdfToRagAsyncRejectsSseEmbed() {
        RagException error = assertThrows(RagException.class,
                () -> controller.importPdfToRagAsync(
                        pdf(), 1L, null, "sse"));
        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("embed=sse"));
    }

    @Test
    void nonSkipPolicyDelegatesToPolicyImport() throws Exception {
        when(pdfImportService.importPdf(any(), any())).thenReturn(importResult());
        when(pdfToRagService.importPdfToRag(
                anyString(), anyString(), eq(1L),
                eq(EmbeddingPolicy.SYNC), eq(false)))
                .thenReturn(ragResult());

        ResponseEntity<Object> response = controller.importPdfToRagWithoutEmbedding(
                pdf(), 1L, null, EmbeddingPolicy.SYNC);

        assertEquals(200, response.getStatusCode().value());
        PdfToRagResponse body = (PdfToRagResponse) response.getBody();
        assertNotNull(body);
        assertEquals("DONE", body.embedStatus());
    }

    @Test
    void skipPathMapsIllegalArgumentToBadRequest() throws Exception {
        when(pdfImportService.importPdf(any(), any()))
                .thenThrow(new IllegalArgumentException("bad pdf"));

        ResponseEntity<Object> response = controller.importPdfToRagWithoutEmbedding(
                pdf(), 1L, null, null);

        assertEquals(400, response.getStatusCode().value());
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertNotNull(body);
        assertEquals("bad pdf", body.getMessage());
    }

    @Test
    void skipPathMapsUnexpectedToInternalServerError() throws Exception {
        when(pdfImportService.importPdf(any(), any()))
                .thenThrow(new RuntimeException("boom"));

        ResponseEntity<Object> response = controller.importPdfToRagWithoutEmbedding(
                pdf(), 1L, null, null);

        assertEquals(500, response.getStatusCode().value());
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertNotNull(body);
        assertTrue(body.getMessage().contains("boom"));
    }

    @Test
    void skipPathRethrowsSecurityException() throws Exception {
        when(pdfImportService.importPdf(any(), any()))
                .thenThrow(new SecurityException("denied"));

        assertThrows(SecurityException.class,
                () -> controller.importPdfToRagWithoutEmbedding(
                        pdf(), 1L, null, null));
    }

    @Test
    void withoutEmbeddingTwoArgOverloadDelegates() throws Exception {
        when(pdfImportService.importPdf(any(), any())).thenReturn(importResult());
        when(pdfToRagService.importPdfToRag(
                anyString(), anyString(), eq(1L), eq(false), eq(false)))
                .thenReturn(ragResult());

        ResponseEntity<Object> response =
                controller.importPdfToRagWithoutEmbedding(pdf(), 1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("uuid-1", ((PdfToRagResponse) response.getBody()).uuid());
    }

    @Test
    void withEmbeddingTwoArgOverloadStreamsProgressAndDone()
            throws Exception {
        stubStreamingImport();

        ResponseEntity<SseEmitter> response =
                controller.importPdfToRagWithEmbedding(pdf(), 1L);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        // 原来这里是 Thread.sleep(500) + 就此结束。改成断言控制器确实带着一个
        // progress consumer 委派出去、并且那个 consumer 被真的调过。
        //
        // **不覆盖的部分**：done 帧本身在这里看不到——直接调控制器时 SseEmitter 没有
        // handler，send 只是暂存。要断言 done 帧得走 MockMvc 的 async 通道，
        // 与 RagDocumentControllerEmbedStreamTest（Batch 946）同一套路。
        assertTrue(progressFired.await(3, java.util.concurrent.TimeUnit.SECONDS),
                "进度回调没有被调过，progress 流式这条路径根本没被走");
        org.mockito.Mockito.verify(pdfToRagService)
                .importPdfToRagWithEmbedding(
                        anyString(), anyString(), any(), anyBoolean(),
                        any(Consumer.class));
    }

    @Test
    void defaultEmbeddingEndpointDelegatesToSseStart() throws Exception {
        stubStreamingImport();

        ResponseEntity<SseEmitter> response =
                controller.importPdfToRagWithEmbeddingDefault(pdf(), 1L, null);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(progressFired.await(3, java.util.concurrent.TimeUnit.SECONDS),
                "默认重载没有委派到带 progress 的流式导入");
        org.mockito.Mockito.verify(pdfToRagService)
                .importPdfToRagWithEmbedding(
                        anyString(), anyString(), any(), anyBoolean(),
                        any(Consumer.class));
    }

    @Test
    void triggerEmbeddingSyncRejectsBlankUuid() {
        ResponseEntity<Object> response = controller.triggerEmbeddingSync(
                "  ", 1L, true);

        assertEquals(400, response.getStatusCode().value());
        assertTrue(((ErrorResponse) response.getBody())
                .getMessage().contains("UUID must not be blank"));
    }

    @Test
    void triggerEmbeddingSyncMapsServiceExceptions() {
        when(pdfToRagService.triggerEmbedding(eq("uuid-1"), eq(1L), eq(true)))
                .thenThrow(new IllegalArgumentException("unknown uuid"));

        assertEquals(400, controller.triggerEmbeddingSync(
                "uuid-1", 1L, null, true, null).getStatusCode().value());

        when(pdfToRagService.triggerEmbedding(eq("uuid-1"), eq(1L), eq(true)))
                .thenThrow(new RuntimeException("boom"));
        ResponseEntity<Object> error = controller.triggerEmbeddingSync(
                "uuid-1", 1L, true);
        assertEquals(500, error.getStatusCode().value());
        assertTrue(((ErrorResponse) error.getBody())
                .getMessage().contains("Embedding trigger failed"));
    }

    @Test
    void triggerEmbeddingSyncThreeArgOverloadReturnsResult() {
        when(pdfToRagService.triggerEmbedding("uuid-1", 1L, true))
                .thenReturn(ragResult());

        ResponseEntity<Object> response = controller.triggerEmbeddingSync(
                "uuid-1", 1L, true);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("uuid-1", ((PdfToRagResponse) response.getBody()).uuid());
    }

    @Test
    void triggerEmbeddingSseThreeArgOverloadStartsStream() throws Exception {
        // 原来这里 stub 的是 `triggerEmbedding(...)`，而 SSE 路径
        // （streamEmbeddingWithProgress）调的是 **`triggerEmbeddingWithProgress(...)`**。
        // 桩从未命中 → 虚拟线程里拿到 null result → NPE → 被 catch 里的 sendError 吞掉。
        // 用例只断言 emitter 非 null，于是**一直在验证一条 NPE 路径**。
        when(pdfToRagService.triggerEmbeddingWithProgress(
                eq("uuid-1"), eq(1L), eq(true), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<EmbedProgressEvent> progress = invocation.getArgument(3);
                    progress.accept(new EmbedProgressEvent(
                            "EMBEDDING", 1, 1, "halfway", null));
                    return ragResult();
                });

        ResponseEntity<SseEmitter> response = controller.triggerEmbeddingSse(
                "uuid-1", 1L, true);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        // 原来是"等待其完成"然后什么都不断言。现在钉住真正可观测的那件事。
        //
        // 必须用 Mockito 的 timeout(...) 而不是裸 verify：这条链跑在**虚拟线程**上
        // （日志里的 `[virtual-36]`），方法返回时那次调用多半还没发生，裸 verify 会
        // 立刻失败。这也是原来那处 Thread.sleep(500) 真正在等的东西——盲等，
        // 等到了也什么都不断言。
        org.mockito.Mockito.verify(pdfToRagService,
                org.mockito.Mockito.timeout(3_000).times(1))
                .triggerEmbeddingWithProgress(
                        eq("uuid-1"), eq(1L), eq(true), any(Consumer.class));
    }
}
