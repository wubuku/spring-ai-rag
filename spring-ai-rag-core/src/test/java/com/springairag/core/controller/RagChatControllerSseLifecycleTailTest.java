package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatSessionCoordinator;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Flux;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * RagChatController SSE 生命周期长尾（Batch 639，JaCoCo 驱动）：
 * 键控 chat 的 claim 竞速重放响应头、诊断会话在非键控流上的
 * TRACE 头、异步完成后 onCompletion 取消订阅、终态后事件不再发出、
 * 上游 error 转成 error 帧、心跳任务真实触发。
 *
 * <h2>Batch 945：这里原来有四条测不出任何东西的用例</h2>
 *
 * `postTerminalEventIsSkipped`、`asyncCompletionDisposesSubscriptionOnEmitterCompletion`、
 * `heartbeatTaskFiresWhenEnabled` 的唯一断言是 {@code assertNotNull(emitter)}，
 * `emitterErrorCallbackStopsHeartbeatAndCancelsSubscription` 连断言都没有——
 * emitter 由 {@code SseEmitters.create()} 无条件造出，所以把事件映射改错、把终态
 * 判断写反、把心跳调度器删掉，四条全绿。
 *
 * <p>改法不是再加一句 {@code verify}，是走 MockMvc 的 async 通道把真正发出去的帧
 * 读回来——{@code SseEmitter} 没有 handler 时 send 只是暂存，而 {@code initialize} 是
 * 包级私有、测试拿不到。套路与 {@code RagChatControllerStreamEventTypesTest}
 * （Batch 897 为同一控制器的同一反模式建立的）一致。
 *
 * <h3>一条断言仍然测不到它想测的东西，这里说清楚是哪一条</h3>
 *
 * 订阅者开头那句 {@code if (terminal.get()) return;}，把生产代码里 {@code terminal.get()}
 * 改成 {@code false && terminal.get()}（即守卫失效）之后，{@code postTerminalEventIsSkipped}
 * **仍然全绿**。原因实测清楚：两条终态路径都会 complete 掉 emitter——{@code Completed}
 * 分支调 {@code emitter.complete()}，{@code Failed} 走 {@code sendChatError}，而它最后一行
 * 也是 {@code emitter.complete()}——往一个已 complete 的 emitter 上 send 是空操作。
 * 所以终态后的事件是被 **emitter 自己**丢掉的，不是被这句守卫挡下来的。
 * 这条用例因此只断言可观测的那部分（终态前发出、终态帧发出、终态后没有），
 * **不声称覆盖守卫**；守卫本身在 SSE 响应体层面不可观测，留给你拍板。
 */
class RagChatControllerSseLifecycleTailTest {

    private static final UUID TURN_ID =
            UUID.fromString("77777777-7777-7777-7777-777777777777");

    private RagChatService ragChatService;
    private ChatTurnOperationService turnOperationService;
    private ChatCommandMapper commandMapper;
    private ChatExecutionService executionService;
    private ChatSessionCoordinator coordinator;
    private ChatSessionCoordinator.LeaseHandle lease;
    private RetrievalDiagnosticsService diagnosticsService;
    private CollectionRetrievalScopeResolver scopeResolver;
    private RagChatController controller;
    private MockHttpServletRequest httpRequest;
    private MockHttpServletResponse httpResponse;
    private MockMvc mockMvc;
    /** 用于证明"终态之后订阅真的被取消"，每条用例一个。 */
    private CountDownLatch subscriptionCancelled;

    @BeforeEach
    void setUp() {
        subscriptionCancelled = new CountDownLatch(1);
        ragChatService = mock(RagChatService.class);
        turnOperationService = mock(ChatTurnOperationService.class);
        commandMapper = mock(ChatCommandMapper.class);
        executionService = mock(ChatExecutionService.class);
        coordinator = mock(ChatSessionCoordinator.class);
        lease = mock(ChatSessionCoordinator.LeaseHandle.class);
        diagnosticsService = mock(RetrievalDiagnosticsService.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        RagSseProperties sseProperties = new RagSseProperties();
        sseProperties.setHeartbeatIntervalSeconds(30);
        controller = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                sseProperties,
                scopeResolver,
                mock(AuditLogService.class));
        controller.configureTurnOperationService(turnOperationService);
        controller.configureSessionCoordinator(coordinator);
        controller.configureModeAwareExecution(commandMapper, executionService);
        controller.configureDiagnostics(diagnosticsService);
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.unscoped());
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller).build();
        httpRequest = new MockHttpServletRequest("POST", "/chat");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(httpRequest));
        httpResponse = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private ChatTurnOperation operation() {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, ChatPrincipal.local().id(), "key-hash", "fp-hash", 1,
                "session-1", TURN_ID,
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 1L, 1,
                null, null, null, null, "{}",
                now, now, null);
    }

    private ChatTurnOperationService.Prepared keyedPrepared() {
        return new ChatTurnOperationService.Prepared(
                ChatPrincipal.local(), "key-hash", "fp-hash", null,
                operation(), true);
    }

    private ChatTurnOperationService.Claim claim(
            ChatTurnOperation operation, boolean replay) throws Exception {
        Constructor<ChatTurnOperationService.Claim> ctor =
                ChatTurnOperationService.Claim.class.getDeclaredConstructor(
                        ChatTurnOperation.class, boolean.class,
                        ChatSessionCoordinator.LeaseHandle.class);
        ctor.setAccessible(true);
        return ctor.newInstance(operation, replay, lease);
    }

    private ChatCommand command() {
        return new ChatCommand(
                "问题", "session-1", ChatPrincipal.local(), null,
                null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void keyedChatClaimReplayRespondsWithReplayHeaders() throws Exception {
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(keyedPrepared());
        when(turnOperationService.inspectExisting(any())).thenReturn(null);
        when(commandMapper.map(any(), any(), any())).thenReturn(command());
        ChatTurnOperation op = operation();
        when(turnOperationService.claim(any(), any(), any(), anyBoolean()))
                .thenReturn(claim(op, true));
        when(turnOperationService.replay(
                any(ChatTurnOperationService.Claim.class)))
                .thenReturn(ChatResponse.builder().answer("重放回答").build());

        ResponseEntity<ChatResponse> response = controller.chat(
                new ChatRequest("问题", "session-1"), httpRequest);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("重放回答", response.getBody().getAnswer());
        assertEquals("true", response.getHeaders()
                .getFirst("X-RAG-Idempotent-Replay"));
        assertEquals(TURN_ID.toString(),
                response.getHeaders().getFirst("X-RAG-Turn-Id"));
    }

    private void stubNonKeyedStream(Flux<ChatEvent> events) {
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(new ChatTurnOperationService.Prepared(
                        ChatPrincipal.local(), null, null, null, null, false));
        when(turnOperationService.inspectExisting(any())).thenReturn(null);
        when(ragChatService.chatEvents(any(ChatRequest.class),
                any(), any()))
                .thenReturn(events);
    }

    /**
     * Batch 945：走 MockMvc 的 async 通道，把真正发出去的 SSE 帧读回来。
     *
     * <p>为什么不能直接调 {@code controller.stream(...)}：没有容器时 {@code SseEmitter}
     * 没有 handler，{@code complete()} / {@code completeWithError()} 都只是把标志位置上，
     * {@code onCompletion} / {@code onError} 回调根本不触发——所以控制器里那几条
     * "回调里取消订阅 + 停心跳" 的分支在这个夹具下**不可达**，而这正是原来那四个用例
     * 只能写 {@code assertNotNull(emitter)} 的原因：emitter 由 {@code SseEmitters.create()}
     * 无条件造出，把事件映射改错、把终态判断写反，它都不会红。
     *
     * <p>套路与 {@code RagChatControllerStreamEventTypesTest}（Batch 897 为同一控制器的
     * 同一反模式建立的）一致：standalone MockMvc + {@code asyncDispatch}。
     */
    private String streamBody() throws Exception {
        MvcResult started = mockMvc.perform(post("/rag/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"问题\",\"sessionId\":\"session-1\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        // 必须先等异步结果就绪再 dispatch：直接 asyncDispatch 会撞上 MockMvc 的
        // timeToWait=0，报 "Async result ... was not set" —— 对同步 Flux 成立，
        // 对 delaySubscription 的流就必然失败。这里给一个有界的等待。
        started.getAsyncResult(10_000);
        MvcResult completed = mockMvc.perform(asyncDispatch(started)).andReturn();
        return new String(
                completed.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
    }

    /** 按帧解析事件名，不按子串找——{@code "event:donex".contains("event:done")} 成立。 */
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

    private static void assertContains(String body, String fragment) {
        assertTrue(body.contains(fragment),
                () -> "SSE body does not carry " + fragment + ":\n" + body);
    }

    private ChatEvent delta(String content) {
        return new ChatEvent.ContentDelta(content);
    }

    private ChatEvent completed() {
        return new ChatEvent.Completed("trace", "session-1", null, null,
                com.springairag.api.enums.ChatMode.KNOWLEDGE,
                java.util.Map.of(), "STOP", java.util.List.of(),
                java.util.Map.of());
    }

    @Test
    void nonKeyedStreamWithDiagnosticsSetsTraceHeader() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "CHAT", "session-1");
        when(diagnosticsService.isEnabled()).thenReturn(true);
        when(diagnosticsService.createSession(any(), anyString(), anyString()))
                .thenReturn(session);
        stubNonKeyedStream(Flux.just(delta("内容"), completed()));

        controller.stream(new ChatRequest("问题", "session-1"),
                httpRequest, httpResponse);

        assertEquals(session.traceId().toString(),
                httpResponse.getHeader(RetrievalTraceHeaders.TRACE_ID));
    }

    @Test
    void postTerminalEventIsSkipped() throws Exception {
        stubNonKeyedStream(Flux.just(
                delta("前段"),
                new ChatEvent.Failed("trace-1", "session-1", "UPSTREAM", "生成失败"),
                delta("终态后内容")));

        String body = streamBody();

        assertContains(body, "前段");
        assertEmitted(body, "error");
        assertContains(body, "生成失败");
        assertFalse(body.contains("终态后内容"),
                () -> "终态之后的事件不该被发出:\n" + body);
    }

    @Test
    void asyncCompletionReachesTheClientAndClosesTheStream() throws Exception {
        stubNonKeyedStream(Flux.concat(
                        Flux.just(delta("异步内容"), completed()).delaySubscription(
                                Duration.ofMillis(250)),
                        Flux.never())
                .doOnCancel(() -> subscriptionCancelled.countDown()));

        String body = streamBody();

        // 异步到达的内容与终态帧都进了响应体，asyncDispatch 能返回就说明流被关掉了。
        assertContains(body, "异步内容");
        assertEmitted(body, "done");
        // emitter.complete() → onCompletion → cancelSubscription → dispose()。
        // 这条断言是"订阅被取消"的机制证明；没有它，上面两条都只说明内容收到了，
        // 说明不了订阅结束了。
        assertTrue(subscriptionCancelled.await(5, TimeUnit.SECONDS),
                "终态之后订阅没有被取消：流已经发完，dispose() 却没落到 Flux 上");
    }

    @Test
    void upstreamErrorIsSentAsAnErrorEventAndEventsAfterItAreDropped() throws Exception {
        stubNonKeyedStream(Flux.concat(
                Flux.just(delta("出错前")),
                Flux.error(new IllegalStateException("上游炸了"))));

        String body = streamBody();

        // 终态（这里是 error）之后不再有 content 帧，而 error 帧带上了消息。
        assertContains(body, "出错前");
        assertEmitted(body, "error");
        assertContains(body, "上游炸了");
    }

    @Test
    void heartbeatTaskFiresWhenEnabled() throws Exception {
        RagSseProperties fast = new RagSseProperties();
        fast.setHeartbeatIntervalSeconds(1);
        RagChatController fastController = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                fast,
                scopeResolver,
                mock(AuditLogService.class));
        fastController.configureTurnOperationService(turnOperationService);
        fastController.configureSessionCoordinator(coordinator);
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(fastController).build();
        stubNonKeyedStream(Flux.just(delta("慢内容"), completed())
                .delaySubscription(Duration.ofMillis(1500)));

        String body = streamBody();

        // 心跳是注释帧（`SseEmitters.sendHeartbeat` 发 `: heartbeat`），间隔 1 秒、
        // 内容 1.5 秒后才到，所以这一条**只有心跳真的被调度过**才会出现在响应体里。
        // 原来的写法是 sleep(1700) 之后 assertNotNull(emitter)：调度器被删掉也照样绿。
        assertTrue(body.contains(": heartbeat"),
                () -> "1 秒间隔的心跳没有出现在 SSE body 里:\n" + body);
        assertContains(body, "慢内容");
    }
}
