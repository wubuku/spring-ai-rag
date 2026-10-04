package com.springairag.core.controller;

import com.springairag.api.dto.PdfToRagResponse;
import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfImportService;
import com.springairag.core.service.PdfToRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.springairag.core.service.CollectionIdentityResolver;

/**
 * PdfImportController SSE 任务 lambda 长尾（Batch 571，JaCoCo 驱
 * 动）：异步任务的成功落 done 并完成 emitter、IAE 与非预期异常落
 * error 通道完成 emitter、发送失败为 best-effort 不挂死。
 *
 * <p>Batch 886。原来四个用例里，三个叫 {@code sseTaskSendsXxx} 的只调用
 * {@code startTask(...)}，而它唯一的断言是"emitter 已 complete"——**发到流上的
 * 事件名和载荷一个字都没验**。把 {@code sendDone} 换成 {@code sendProgress}
 * 这样的改动可以让它们全绿。三个名字承诺的是"发了什么"，body 证明的只是
 * "没抛异常"。现在每条都断言事件名**和**载荷内容。
 */
class PdfImportControllerSseTaskLambdaTailTest {

    private PdfToRagService pdfToRagService;
    private PdfImportController controller;

    private CollectionIdentityResolver collectionIdentityResolver;

    @BeforeEach
    void setUp() {
        pdfToRagService = mock(PdfToRagService.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        controller = new PdfImportController(
                mock(PdfImportService.class),
                mock(MarkdownRendererService.class),
                pdfToRagService,
                collectionIdentityResolver);
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

    /**
     * What the task put on the stream: the literal event-name fragments and the
     * payload objects, kept apart.
     *
     * <p>Batch 886. `SseEmitters.create()` hands back a bare `SseEmitter` with no
     * MVC async context, so every send is buffered in the emitter's private
     * `earlySendAttempts`. A send queues more than one entry — the
     * `event:done` fragment is a String, the response is an object — which is
     * what two earlier attempts got wrong: one read a `StringBuilder` field named
     * `sb` off the event builder, the next threw on the first non-String entry.
     * The runtime shape had to be looked at rather than reasoned about; the
     * assertion now simply asks which of the two kinds each entry is.
     *
     * <p>`earlySendAttempts` is the one piece of Spring internals left. If it
     * moves, this fails loudly instead of quietly ceasing to check anything.
     */
    private static Sent sent(SseEmitter emitter) throws Exception {
        Field attempts = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        attempts.setAccessible(true);
        var buffered = (java.util.Set<?>) attempts.get(emitter);
        StringBuilder names = new StringBuilder();
        List<Object> payloads = new ArrayList<>();
        for (Object attempt : buffered) {
            Object data = attempt.getClass().getMethod("getData").invoke(attempt);
            if (data instanceof String fragment) names.append(fragment);
            else payloads.add(data);
        }
        return new Sent(names.toString(), payloads);
    }

    private record Sent(String events, List<Object> payloads) {
        boolean has(String event) { return events.contains("event:" + event); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorPayload(List<Object> payloads) {
        return payloads.stream()
                .filter(Map.class::isInstance)
                .map(p -> (Map<String, Object>) p)
                .findFirst()
                .orElse(null);
    }

    @Test
    void sseTaskSendsDoneOnSuccess() throws Exception {
        when(pdfToRagService.triggerEmbeddingWithProgress(
                eq("uuid-1"), isNull(), anyBoolean(), any()))
                .thenReturn(result());

        Sent sent = sent(startTask("uuid-1"));

        assertTrue(sent.has("done"),
                "the success path must put a done event on the stream, not merely complete it");
        assertFalse(sent.has("error"), "the success path must not also report an error");
        assertEquals(1, sent.payloads().size(), "one payload expected, got " + sent.payloads());
        assertInstanceOf(PdfToRagResponse.class, sent.payloads().get(0),
                "the success path carries the response, not an error map");
        assertEquals(41L, ((PdfToRagResponse) sent.payloads().get(0)).documentId());
    }

    @Test
    void sseTaskSendsErrorOnIllegalArgument() throws Exception {
        when(pdfToRagService.triggerEmbeddingWithProgress(
                eq("uuid-2"), isNull(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("unknown uuid"));

        Sent sent = sent(startTask("uuid-2"));

        assertTrue(sent.has("error"), "a rejected uuid must surface as an error event");
        assertFalse(sent.has("done"), "a failed trigger must not also report success");
        Map<String, Object> error = errorPayload(sent.payloads());
        assertNotNull(error, "the error event must carry a payload");
        assertEquals("unknown uuid", error.get("error"), "the client is told why");
        assertEquals("uuid-2", error.get("uuid"), "and which request it was");
    }

    @Test
    void sseTaskSendsErrorOnUnexpectedException() throws Exception {
        when(pdfToRagService.triggerEmbeddingWithProgress(
                eq("uuid-3"), isNull(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("storage offline"));

        Sent sent = sent(startTask("uuid-3"));

        assertTrue(sent.has("error"),
                "an unexpected failure must still complete through the error channel");
        assertFalse(sent.has("done"), "a failed trigger must not also report success");
        assertEquals("storage offline", errorPayload(sent.payloads()).get("error"));
        assertTrue(sent.payloads().stream().noneMatch(PdfToRagResponse.class::isInstance),
                "a failed trigger must not also emit the success payload");
    }

    // `sseTaskCompletesEvenWhenSendFails` used to live here. It set up the same
    // success path as `sseTaskSendsDoneOnSuccess` with a different uuid, never
    // made a send fail, and so verified nothing while its name promised the
    // resilience case. That contract is real and is tested where it can be made
    // deterministic — `SseEmittersTest.sendDone_disconnectedClient` completes
    // the emitter first so the send really does fail. A duplicate that
    // misdescribes itself inflates the count and lies in the one place a reader
    // looks to decide whether something is covered.
}
