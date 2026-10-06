package com.springairag.core.controller;

import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.service.DocumentMutationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import com.springairag.core.service.ExternalDocumentService;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.DocumentRelocationService;
import jakarta.servlet.ServletException;

/**
 * embedDocumentStream SSE 端点（Batch 390）：正常进度+done、
 * IllegalArgumentException error 事件、意外异常 completeWithError
 * 三种终止形态。
 *
 * <h2>Batch 946：这里原来三条用例都测不出自己名字里写的事</h2>
 *
 * 三条的断言分别是 {@code assertNotNull(emitter)}，而 emitter 由
 * {@code SseEmitters.create()} 无条件造出——所以把 {@code progress} 事件名改错、
 * 把 {@code done} 换成不发的、把 error 分支删掉，用例全绿。类注释写着
 * "正常进度+done / error 事件 / completeWithError 三种终止形态"，一条都没验。
 *
 * <p>改法：走 MockMvc 的 async 通道把真正发出去的帧读回来。{@code SseEmitter} 没有
 * handler 时 {@code send} 只是暂存、{@code complete()} 只是置标志位，而
 * {@code initialize} 是包级私有、测试拿不到——所以这不是"再加一句 verify"能解决的。
 * 套路与 {@code RagChatControllerStreamEventTypesTest}（Batch 897）、
 * {@code RagChatControllerSseLifecycleTailTest}（Batch 945）一致。
 */
class RagDocumentControllerEmbedStreamTest {

    private DocumentEmbedService documentEmbedService;
    private RagDocumentController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        documentEmbedService = mock(DocumentEmbedService.class);
        controller = new RagDocumentController(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                documentEmbedService,
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                null,
                mock(DocumentMutationService.class),

                mock(ExternalDocumentService.class),


                mock(DocumentDerivationDescriptorProvider.class),



                mock(DocumentRelocationService.class));
        controller.setDispatchService(mock(EmbeddingDispatchService.class));
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .build();
    }

    /** 跑一次真实的 embed/stream 请求，读回完整 SSE 响应体。 */
    private String streamBody(long documentId) throws Exception {
        MvcResult started = mockMvc.perform(post("/rag/documents/{id}/embed/stream", documentId))
                .andExpect(request().asyncStarted())
                .andReturn();
        // 先等异步结果就绪再 dispatch：直接 asyncDispatch 会撞 MockMvc 的
        // timeToWait=0，报 "Async result ... was not set"。
        started.getAsyncResult(10_000);
        MvcResult completed = mockMvc.perform(asyncDispatch(started)).andReturn();
        return new String(
                completed.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
    }

    private static List<String> eventNames(String body) {
        return java.util.Arrays.stream(body.split("\n\n"))
                .map(frame -> frame.lines()
                        .filter(line -> line.startsWith("event:"))
                        .map(line -> line.substring("event:".length()).trim())
                        .findFirst()
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static void assertEmitted(String body, String eventName) {
        List<String> names = eventNames(body);
        assertTrue(names.contains(eventName),
                () -> "SSE body carries no " + eventName + " event, only " + names + ":\n" + body);
    }

    private static void assertNotEmitted(String body, String eventName) {
        List<String> names = eventNames(body);
        assertFalse(names.contains(eventName),
                () -> "SSE body must not carry " + eventName + ", but has " + names + ":\n" + body);
    }

    @Test
    void embedStreamSendsProgressAndDoneOnSuccess() throws Exception {
        when(documentEmbedService.embedDocumentWithProgress(
                eq(41L), eq(false), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<com.springairag.api.dto.EmbedProgressEvent> progress =
                    invocation.getArgument(2);
            progress.accept(new com.springairag.api.dto.EmbedProgressEvent(
                    "CHUNKING", 1, 2, "halfway", 41L));
            return Map.of("status", "COMPLETED");
        });

        String body = streamBody(41L);

        assertEmitted(body, "progress");
        assertEmitted(body, "done");
        assertTrue(body.contains("CHUNKING"), () -> "progress 帧没带上阶段名:\n" + body);
        assertTrue(body.contains("halfway"), () -> "progress 帧没带上阶段消息:\n" + body);
        assertTrue(body.contains("\"documentId\":41"), () -> "done 帧没带上 documentId:\n" + body);
    }

    @Test
    void embedStreamSendsErrorEventOnIllegalArgument() throws Exception {
        when(documentEmbedService.embedDocumentWithProgress(
                any(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("document not found"));

        String body = streamBody(41L);

        // IllegalArgumentException 走 sendError：**发一个 error 帧再正常 complete**，
        // 不是 completeWithError——所以 done 帧不该出现。
        assertEmitted(body, "error");
        assertTrue(body.contains("document not found"),
                () -> "error 帧没带上原始消息:\n" + body);
        assertNotEmitted(body, "done");
    }

    @Test
    void embedStreamCompletesWithErrorOnUnexpectedFailure() {
        when(documentEmbedService.embedDocumentWithProgress(
                any(), anyBoolean(), any()))
                .thenThrow(new RagException(
                        com.springairag.api.enums.ErrorCode.INTERNAL_ERROR,
                        "boom"));

        // completeWithError 的可观测形态**不是响应体，而是请求直接以错误结束**：
        // 实测抛 jakarta.servlet.ServletException("Request processing failed: ...")，
        // cause 是原始的 RagException("boom")，客户端一个帧也拿不到。
        //
        // 这与上一条正是两条分支的差别所在——IllegalArgumentException 发一个 error 帧
        // 再正常 complete，意外异常则整个流以错误收场。原来两条都只写
        // assertNotNull(emitter)，所以这个差别谁也没测。
        ServletException thrown =
                assertThrows(ServletException.class, () -> streamBody(41L));
        Throwable root = thrown.getCause() != null ? thrown.getCause() : thrown;
        assertInstanceOf(RagException.class, root,
                () -> "根因应是 RagException，实际: " + root);
        assertEquals("boom", root.getMessage());
    }
}
