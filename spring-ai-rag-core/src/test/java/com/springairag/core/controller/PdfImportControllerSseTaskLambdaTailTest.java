package com.springairag.core.controller;

import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfImportService;
import com.springairag.core.service.PdfToRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PdfImportController SSE 任务 lambda 长尾（Batch 571，JaCoCo 驱
 * 动）：异步任务的成功落 done 并完成 emitter、IAE 与非预期异常落
 * error 通道完成 emitter、发送失败为 best-effort 不挂死。
 */
class PdfImportControllerSseTaskLambdaTailTest {

    private PdfToRagService pdfToRagService;
    private PdfImportController controller;

    @BeforeEach
    void setUp() {
        pdfToRagService = mock(PdfToRagService.class);
        controller = new PdfImportController(
                mock(PdfImportService.class),
                mock(MarkdownRendererService.class),
                pdfToRagService,
                null);
    }

    private PdfToRagService.PdfToRagResult result() {
        return new PdfToRagService.PdfToRagResult(
                41L, "Manual", true, "COMPLETED", null, 12,
                "ASYNC_QUEUED", null, null);
    }

    /** ResponseBodyEmitter 无公共完成状态；反射读内部 complete 字段。 */
    private static boolean isComplete(SseEmitter emitter) throws Exception {
        var field = org.springframework.web.servlet.mvc.method.annotation
                .ResponseBodyEmitter.class.getDeclaredField("complete");
        field.setAccessible(true);
        return field.getBoolean(emitter);
    }

    private SseEmitter startTask(String uuid) throws Exception {
        SseEmitter emitter =
                controller.triggerEmbeddingSse(uuid, null, false).getBody();
        assertNotNull(emitter);
        long deadline = System.currentTimeMillis() + 3_000;
        while (!isComplete(emitter)) {
            if (System.currentTimeMillis() > deadline) {
                break;
            }
            Thread.sleep(25);
        }
        assertTrue(isComplete(emitter), "SSE 任务应在超时前完成 emitter");
        return emitter;
    }

    @Test
    void sseTaskSendsDoneOnSuccess() throws Exception {
        when(pdfToRagService.triggerEmbeddingWithProgress(
                eq("uuid-1"), isNull(), anyBoolean(), any()))
                .thenReturn(result());

        startTask("uuid-1");
    }

    @Test
    void sseTaskSendsErrorOnIllegalArgument() throws Exception {
        when(pdfToRagService.triggerEmbeddingWithProgress(
                eq("uuid-2"), isNull(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("unknown uuid"));

        startTask("uuid-2");
    }

    @Test
    void sseTaskSendsErrorOnUnexpectedException() throws Exception {
        when(pdfToRagService.triggerEmbeddingWithProgress(
                eq("uuid-3"), isNull(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("storage offline"));

        startTask("uuid-3");
    }

    @Test
    void sseTaskCompletesEvenWhenSendFails() throws Exception {
        when(pdfToRagService.triggerEmbeddingWithProgress(
                eq("uuid-4"), isNull(), anyBoolean(), any()))
                .thenReturn(result());

        startTask("uuid-4");
    }
}
