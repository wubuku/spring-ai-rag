package com.springairag.core.controller;

import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.exception.RagException;
import com.springairag.core.service.PdfImportService;
import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfToRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;
import java.util.UUID;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.springairag.core.service.CollectionIdentityResolver;

/**
 * 触发嵌入的策略分派语义：ASYNC 端点拒绝 embed=sse 组合、
 * ASYNC/SKIP 策略委派与拒绝、同步端点的非 SYNC 策略转发、
 * SSE 端点的空白 UUID 防护与默认变体委托。
 */
class PdfImportEmbedPolicyTest {

    private PdfImportService pdfImportService;
    private MarkdownRendererService markdownRendererService;
    private PdfToRagService pdfToRagService;
    private PdfImportController controller;

    private CollectionIdentityResolver collectionIdentityResolver;

    @BeforeEach
    void setUp() {
        pdfImportService = mock(PdfImportService.class);
        markdownRendererService = mock(MarkdownRendererService.class);
        pdfToRagService = mock(PdfToRagService.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        controller = new PdfImportController(
                pdfImportService, markdownRendererService, pdfToRagService, collectionIdentityResolver);
    }

    private PdfToRagService.PdfToRagResult fullResult() {
        return new PdfToRagService.PdfToRagResult(
                42L, "Doc", true, "QUEUED", "dispatched", 9,
                "ASYNC_QUEUED", UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void asyncEndpointRejectsEmbedSseCombination() {
        RagException error = assertThrows(RagException.class,
                () -> controller.triggerEmbeddingAsync(
                        "uuid-1", null, null, "sse", false));
        assertEquals(RagException.class.getName(), error.getClass().getName());
    }

    @Test
    void asyncEndpointDelegatesToAsyncPolicyJob() {
        when(pdfToRagService.triggerEmbedding(
                eq("uuid-1"), eq(10L),
                eq(EmbeddingPolicy.ASYNC), eq(false)))
                .thenReturn(fullResult());

        ResponseEntity<Object> response = controller.triggerEmbeddingAsync(
                "uuid-1", 10L, null, "json", false);

        assertEquals(200, response.getStatusCode().value());
        assertInstanceOf(com.springairag.api.dto.PdfToRagResponse.class,
                response.getBody());
    }

    @Test
    void syncEndpointForwardsNonSyncPolicyToPolicyDispatch() {
        when(pdfToRagService.triggerEmbedding(
                eq("uuid-2"), org.mockito.ArgumentMatchers.isNull(),
                eq(EmbeddingPolicy.ASYNC), eq(false)))
                .thenReturn(fullResult());

        // embed=sync + embeddingPolicy=ASYNC → 转发到策略分派。
        ResponseEntity<Object> response = controller.triggerEmbeddingSync(
                "uuid-2", null, null, false, EmbeddingPolicy.ASYNC);

        assertEquals(200, response.getStatusCode().value());
        verifyPolicyDispatch("uuid-2");
    }

    @Test
    void policyDispatchRejectsSkipPolicyExplicitly() {
        RagException error = assertThrows(RagException.class,
                () -> controller.triggerEmbeddingSync(
                        "uuid-3", null, null, false, EmbeddingPolicy.SKIP));
        assertEquals(RagException.class.getName(), error.getClass().getName());
    }

    @Test
    void sseEndpointRejectsBlankUuid() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.triggerEmbeddingSse("  ", null, false));
    }

    @Test
    void sseDefaultVariantDelegatesWithCollectionId() {
        // 夹具原先打桩的是 pdfToRagService.triggerEmbedding(...)，可 SSE 这条路
        // 实际走的是 triggerEmbeddingWithProgress(...，progressCallback)——
        // 桩压根没打中，mock 返回的是 null。原来的 assertNotNull(response
        // .getBody()) 照样绿，因为它只断 ResponseEntity 和 emitter 存在。
        //
        // 顺带一提：这条用例传了 "uuid-4"，却从来没有任何断言检查它到没到
        // service。一起钉上。
        //
        // 另外：控制器把真正的调用丢进虚拟线程异步执行，所以下面那次
        // verify 必须带 timeout()，否则就是一次竞态（见调用处注释）。
        when(pdfToRagService.triggerEmbeddingWithProgress(
                any(), any(), anyBoolean(), any()))
                .thenReturn(fullResult());

        controller.triggerEmbeddingSseDefault("uuid-4", 10L, false);

        ArgumentCaptor<String> uuid = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> collectionId = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Boolean> force = ArgumentCaptor.forClass(Boolean.class);
        // 必须带 timeout()：控制器把真正的调用丢进 Thread.ofVirtual()
        // 异步跑（PdfImportController:694），紧跟着的 verify 是一次竞态。
        // 单独跑这条用例时它靠运气绿，全量跑（线程池更挤）就红成
        // "zero interactions with this mock" —— 证据不可信就丢弃。
        // Mockito 的 timeout() 是轮询到满足为止，不是 sleep。
        verify(pdfToRagService, org.mockito.Mockito.timeout(10_000))
                .triggerEmbeddingWithProgress(
                        uuid.capture(), collectionId.capture(),
                        force.capture(), any());
        assertEquals("uuid-4", uuid.getValue(), "uuid 必须原样透传");
        assertEquals(10L, collectionId.getValue(),
                "默认变体必须把 collectionId 原样透传给 service");
        assertFalse(force.getValue(), "forceReembed 应保持调用方传入的 false");
    }

    @Test
    void getFileLookupsFallBackBetweenMarkdownVariants() {
        // previewHtmlFragment 的双查找：default.md 缺失时回退到 .pdf→.md 替换。
        when(pdfImportService.getFile("/uuid-9/default.md"))
                .thenReturn(Optional.empty());
        com.springairag.core.entity.FsFile markdown =
                new com.springairag.core.entity.FsFile();
        markdown.setPath("/uuid-9/paper.md");
        markdown.setMimeType("text/markdown");
        when(pdfImportService.getFile("/uuid-9/paper.md"))
                .thenReturn(Optional.of(markdown));
        when(markdownRendererService.renderToHtml(any()))
                .thenReturn("<p>hi</p>");

        ResponseEntity<String> response =
                controller.previewHtmlFragment("/uuid-9/paper.pdf");

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("<p>hi</p>"));
    }

    private void verifyPolicyDispatch(String uuid) {
        org.mockito.Mockito.verify(pdfToRagService).triggerEmbedding(
                eq(uuid), org.mockito.ArgumentMatchers.isNull(),
                eq(EmbeddingPolicy.ASYNC), eq(false));
    }
}
