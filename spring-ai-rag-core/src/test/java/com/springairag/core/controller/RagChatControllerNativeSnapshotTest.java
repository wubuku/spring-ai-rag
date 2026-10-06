package com.springairag.core.controller;

import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ChatSource;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * nativeSnapshotEmitter 残余（Batch 376）：keyed claim 响应头、
 * 完整/空 ChatResponse 的 content/sources/done 事件链与 turnId
 * 回退分支。
 *
 * <h2>Batch 947：原来靠反射调私有方法，三条断言只验了 emitter 非 null</h2>
 *
 * 原来的两条事件用例是 {@code getDeclaredMethod("nativeSnapshotEmitter", ...)} +
 * {@code setAccessible(true)} 直接调私有方法，然后 {@code assertNotNull(emitter)}。
 * emitter 由 {@code SseEmitters.create()} 无条件造出，所以"发了哪些帧""空答案时跳过
 * content 帧"这两件事一条都没验；而反射调私有方法本身绕过了 HTTP 通道，claim 为 null
 * 的那条更是**生产路径上到不了的分支**（{@code replayNativeSse} 只在
 * {@code existing != null} 时调用，{@code executeKeyedSse} 的 claim 由构造保证非空）。
 *
 * <p>改法：走 {@code POST /rag/chat/stream} 的重放路径——
 * {@code inspectExisting} 返回一个 {@code replay()} 为 true 的 Claim，
 * 控制器就会进 {@code replayNativeSse} → {@code nativeSnapshotEmitter}。
 * 这条路径既是生产上真实会走的，也给断言提供了可读的对象：响应体里的帧与响应头。
 *
 * <p><b>不覆盖的部分写在这里</b>：{@code nativeSnapshotEmitter} 里
 * {@code claim == null} 的分支现在没有用例了。它在两条生产路径上都不可达，属于防御性
 * 代码；删不删交给你拍板（与 {@code RagChatController} 订阅者开头那句
 * {@code if (terminal.get()) return;} 同一类）。
 */
class RagChatControllerNativeSnapshotTest {

    private static final UUID TURN_ID =
            UUID.fromString("88888888-8888-8888-8888-888888888888");

    private ChatTurnOperationService turnOperationService;
    private ChatResponse chatResponse;
    private RagChatController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        turnOperationService = mock(ChatTurnOperationService.class);
        chatResponse = mock(ChatResponse.class);
        when(chatResponse.getSessionId()).thenReturn("session-1");
        when(chatResponse.getTraceId()).thenReturn("trace-1");
        controller = new RagChatController(
                mock(com.springairag.core.config.RagChatService.class),
                mock(com.springairag.core.repository.RagChatHistoryRepository.class),
                mock(com.springairag.core.service.ChatExportService.class),
                null,
                scopeResolver(),
                null);
        controller.configureTurnOperationService(turnOperationService);
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .build();
    }

    private com.springairag.core.service.CollectionRetrievalScopeResolver scopeResolver() {
        var resolver = mock(com.springairag.core.service.CollectionRetrievalScopeResolver.class);
        when(resolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(com.springairag.core.retrieval.RetrievalScope.unscoped());
        return resolver;
    }

    private ChatTurnOperation operation() {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, ChatPrincipal.local().id(), "key-hash", "fp-hash", 1,
                "session-1", TURN_ID,
                ChatTurnOperation.Transport.NATIVE_SSE,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 1L, 1,
                null, null, null, null, "{}",
                now, now, null);
    }

    /** 让 stream() 走重放分支：inspectExisting 返回一个 replay 的 Claim。 */
    private void stubReplay(boolean keyed, String answer, List<ChatSource> sources) {
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(new ChatTurnOperationService.Prepared(
                        ChatPrincipal.local(), keyed ? "key-hash" : null,
                        keyed ? "fp-hash" : null, null,
                        keyed ? operation() : null, keyed));
        when(turnOperationService.inspectExisting(any())).thenReturn(
                new ChatTurnOperationService.Claim(
                        keyed ? operation() : null, true));
        when(chatResponse.getAnswer()).thenReturn(answer);
        when(chatResponse.getSources()).thenReturn(sources);
        when(turnOperationService.replay(any(ChatTurnOperationService.Claim.class)))
                .thenReturn(chatResponse);
    }

    private MvcResult replayStream() throws Exception {
        return mockMvc.perform(post("/rag/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"问题\",\"sessionId\":\"session-1\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private String replayBody() throws Exception {
        MvcResult started = replayStream();
        // 先等异步结果就绪再 dispatch，否则撞 MockMvc 的 timeToWait=0。
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

    @Test
    void replayEmitsContentSourcesAndDone() throws Exception {
        stubReplay(true, "answer text", List.of(mock(ChatSource.class)));

        String body = replayBody();

        // 三个帧是这个用例的全部内容：非空答案发 content、非空来源发 sources、
        // 收尾永远发 done。少了任何一个，这里都会红。
        assertEmitted(body, "content");
        assertEmitted(body, "sources");
        assertEmitted(body, "done");
        assertTrue(body.contains("answer text"),
                () -> "content 帧没带上答案:\n" + body);
        assertTrue(body.contains("\"idempotentReplay\":true"),
                () -> "done 帧没标出这是重放:\n" + body);
        assertTrue(body.contains(TURN_ID.toString()),
                () -> "done 帧的 turnId 应取自 claim 的 operation:\n" + body);
    }

    @Test
    void emptyAnswerAndSourcesSkipContentAndSourcesEvents() throws Exception {
        stubReplay(true, "", List.of());

        String body = replayBody();

        // 空答案、空来源 → 只剩 done。原来这条只断言 emitter 非 null，
        // 所以把 `if (response.getAnswer() != null && !isEmpty())` 改成无条件发帧，
        // 它照样绿。
        assertNotNull(body);
        assertFalse(body.contains("event:content"),
                () -> "空答案不该发 content 帧:\n" + body);
        assertFalse(body.contains("event:sources"),
                () -> "空来源不该发 sources 帧:\n" + body);
        assertEmitted(body, "done");
    }

    @Test
    void keyedClaimWritesTurnHeaders() throws Exception {
        stubReplay(true, null, List.of());

        MvcResult started = replayStream();
        started.getAsyncResult(10_000);
        MvcResult completed = mockMvc.perform(asyncDispatch(started)).andReturn();

        assertNotNull(completed.getResponse().getHeader("X-RAG-Turn-Id"));
        assertEquals(TURN_ID.toString(), completed.getResponse().getHeader("X-RAG-Turn-Id"));
        assertEquals("true", completed.getResponse().getHeader("X-RAG-Idempotent-Replay"));
    }
}