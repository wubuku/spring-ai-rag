package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatSessionCoordinator;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagChatController SSE 取消竞态（Batch 771）。
 *
 * <p>{@code stream()} 里订阅的取消回调此前从未被任何测试真正触发过：
 * JaCoCo 显示 {@code if (disposable != null)} 两个分支<b>各自 0 覆盖</b>。
 * 原因不是没有测试，而是 Batch 639 的两个用例名字写着
 * "asyncCompletionDisposesSubscriptionOnEmitterCompletion" 与
 * "emitterErrorCallbackStopsHeartbeatAndCancelsSubscription"，
 * 断言却只有 {@code assertNotNull(emitter)}——<b>声称覆盖却什么都没验</b>。
 * 那一批的覆盖率是假绿。
 *
 * <p>本类做三件旧用例没做的事：
 * <ol>
 *   <li>用 {@code doOnCancel} 计数真实取消次数，而不是断言 emitter 非空；</li>
 *   <li>直接取出 emitter 上注册的 {@code timeoutCallback} /
 *       {@code errorCallback} / {@code completionCallback} 并触发，
 *       覆盖三条取消入口；</li>
 *   <li>重复触发，验证 {@code getAndSet(null)} 让第二次成为无害的空操作
 *       ——这正是"连接抖动导致回调重入"的真实形态。</li>
 * </ol>
 *
 * <p><b>本类钉住什么、不钉什么（变异实测，如实记录）</b>：
 * <ul>
 *   <li>把 {@code cancelSubscription} 整体改成空操作 → <b>5 个用例变红</b>；</li>
 *   <li>去掉 {@code if (disposable != null)} 守卫、直接 dispose → <b>1 失败 + 1 NPE</b>；</li>
 *   <li>把 {@code getAndSet(null)} 换成 {@code get()} → <b>7 个用例仍然全绿</b>。
 *       原因是 Reactor 的 {@code Disposable.dispose()} 契约上就是幂等的，
 *       重复调用不会产生第二个取消信号，两者行为等价。
 *       {@code getAndSet} 的价值是<b>及时释放引用</b>，不是行为差异——
 *       这一点从外部行为无法观察，所以本类不假装覆盖它。</li>
 * </ul>
 */
class RagChatControllerSseCancelRaceTest {

    private RagChatService ragChatService;
    private ChatTurnOperationService turnOperationService;
    private RagChatController controller;
    private MockHttpServletRequest httpRequest;
    private MockHttpServletResponse httpResponse;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        turnOperationService = mock(ChatTurnOperationService.class);
        RagSseProperties sseProperties = new RagSseProperties();
        // 心跳拉到 30s，确保用例窗口内不会真的发心跳干扰断言。
        sseProperties.setHeartbeatIntervalSeconds(30);
        controller = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                sseProperties,
                mock(CollectionRetrievalScopeResolver.class),
                mock(AuditLogService.class));
        controller.configureTurnOperationService(turnOperationService);
        controller.configureSessionCoordinator(
                mock(ChatSessionCoordinator.class));
        httpRequest = new MockHttpServletRequest("POST", "/chat/stream");
        httpRequest.setAttribute("authenticatedPrincipalType", "LOCAL");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(httpRequest));
        httpResponse = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ─── 触发 emitter 上真实注册的回调 ────────────────────────────────

    @SuppressWarnings("unchecked")
    private void fireErrorCallback(SseEmitter emitter, Throwable error) {
        Consumer<Throwable> callback =
                (Consumer<Throwable>) readField(emitter, "errorCallback");
        callback.accept(error);
    }

    private void fireDefaultCallback(SseEmitter emitter, String name) {
        // timeoutCallback / completionCallback 装的是
        // ResponseBodyEmitter.DefaultCallback，它实现的是 Runnable（run()），
        // 不是 java.util.function.Consumer——直接强转会 ClassCastException。
        // 控制器注册的 Runnable 不使用入参，直接 run() 即可。
        Object callback = readField(emitter, name);
        if (!(callback instanceof Runnable runnable)) {
            throw new AssertionError(
                    "预期 " + name + " 是 Runnable，实际是 "
                            + callback.getClass().getName());
        }
        runnable.run();
    }

    private Object readField(SseEmitter emitter, String name) {
        try {
            Field field = SseEmitter.class.getSuperclass()
                    .getDeclaredField(name);
            field.setAccessible(true);
            return field.get(emitter);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(
                    "无法读取 SseEmitter." + name + "，回调触发方式已失效", error);
        }
    }

    // ─── 夹具 ────────────────────────────────────────────────────────

    /** 返回一个永不发射、但记录真实取消次数的流。 */
    private Flux<ChatEvent> neverStream(AtomicInteger cancels) {
        return Flux.<ChatEvent>never()
                .doOnCancel(() -> cancels.incrementAndGet());
    }

    private void stubStream(Flux<ChatEvent> events) {
        when(turnOperationService.prepare(any(), anyList(), any()))
                .thenReturn(new ChatTurnOperationService.Prepared(
                        ChatPrincipal.local(), null, null, null, null, false));
        when(turnOperationService.inspectExisting(any())).thenReturn(null);
        when(ragChatService.chatEvents(any(ChatRequest.class),
                isNull(), isNull()))
                .thenReturn(events);
    }

    private SseEmitter startStream(Flux<ChatEvent> events) {
        stubStream(events);
        SseEmitter emitter = controller.stream(
                new ChatRequest("问题", "session-race"), httpRequest, httpResponse);
        assertNotNull(emitter);
        return emitter;
    }

    private ChatEvent completed() {
        return new ChatEvent.Completed("trace", "session-race", null, null,
                com.springairag.api.enums.ChatMode.KNOWLEDGE,
                java.util.Map.of(), "STOP", java.util.List.of(),
                java.util.Map.of());
    }

    // ─── 三条取消入口 ────────────────────────────────────────────────

    @Test
    void errorCallbackDisposesTheLiveSubscription() {
        AtomicInteger cancels = new AtomicInteger();
        SseEmitter emitter = startStream(neverStream(cancels));

        fireErrorCallback(emitter, new IllegalStateException("模拟容器侧断连"));

        // 旧用例在这里只断言 emitter 非空，于是这条分支一直是 0 覆盖。
        assertEquals(1, cancels.get(),
                "连接错误回调必须真的取消上游订阅");
    }

    @Test
    void timeoutCallbackDisposesTheLiveSubscription() {
        AtomicInteger cancels = new AtomicInteger();
        SseEmitter emitter = startStream(neverStream(cancels));

        fireDefaultCallback(emitter, "timeoutCallback");

        assertEquals(1, cancels.get(), "超时回调必须取消上游订阅");
    }

    @Test
    void completionCallbackDisposesTheLiveSubscription() {
        AtomicInteger cancels = new AtomicInteger();
        SseEmitter emitter = startStream(neverStream(cancels));

        fireDefaultCallback(emitter, "completionCallback");

        assertEquals(1, cancels.get(), "异步完成回调必须取消上游订阅");
    }

    // ─── 重复取消：getAndSet(null) 之后的空操作分支 ──────────────────

    @Test
    void repeatedCancelIsIdempotentAndDisposesOnlyOnce() {
        AtomicInteger cancels = new AtomicInteger();
        SseEmitter emitter = startStream(neverStream(cancels));

        fireDefaultCallback(emitter, "timeoutCallback");
        fireErrorCallback(emitter, new IllegalStateException("先超时后断连"));
        fireDefaultCallback(emitter, "completionCallback");

        // 三条取消入口都触发时，上游只能被取消一次。
        // 注意：这条断言钉的是"重复取消无害"，不是"引用被清空"——
        // 后者如类注释所述无法从外部行为观察。
        assertEquals(1, cancels.get(), "重复取消不得重复处置同一个订阅");
    }

    @Test
    void cancelAfterStreamAlreadyTerminatedIsSafe() {
        AtomicInteger cancels = new AtomicInteger();
        SseEmitter emitter = startStream(neverStream(cancels));

        fireErrorCallback(emitter, new IllegalStateException("先断连"));
        assertDoesNotThrow(
                () -> fireErrorCallback(
                        emitter, new IllegalStateException("再断连一次")),
                "重复触发错误回调不得抛出");

        assertEquals(1, cancels.get());
    }

    // ─── 终态幂等：上游先完成再报错 ──────────────────────────────────

    @Test
    void upstreamErrorAfterCompletionDoesNotResurfaceOnAClosedEmitter() {
        // 真实形态：上游已经 Completed 收流，连接随后才报错。
        // 终态已置位，错误消费者必须整体跳过，不能再往已关闭的
        // emitter 上补发一条 error 事件。
        SseEmitter emitter = startStream(Flux.concat(
                Flux.just(completed()),
                Flux.<ChatEvent>error(new IllegalStateException("收流后才断"))));

        assertThrows(IllegalStateException.class,
                () -> emitter.send(SseEmitter.event().data("late")),
                "Completed 之后 emitter 必须已关闭");
    }

    @Test
    void terminalEventTwiceStillClosesTheStreamOnce() {
        AtomicInteger cancels = new AtomicInteger();
        SseEmitter emitter = startStream(Flux.concat(
                Flux.just(completed()),
                Flux.<ChatEvent>never().doOnCancel(() -> cancels.incrementAndGet())));

        assertThrows(IllegalStateException.class,
                () -> emitter.send(SseEmitter.event().data("late")));

        // 终态后到达的事件被 terminal 守卫丢弃，取消计数不得增加。
        fireErrorCallback(emitter, new IllegalStateException("事后断连"));
        assertEquals(1, cancels.get(),
                "终态后取消仍应处置订阅一次，但事件不得再被处理");
    }
}
