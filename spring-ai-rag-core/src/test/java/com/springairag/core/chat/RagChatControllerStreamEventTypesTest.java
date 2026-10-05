package com.springairag.core.chat;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.controller.RagChatController;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * RagChatController.stream SSE 事件类型映射长尾（Batch 508，JaCoCo
 * 驱动，测试置于 core.chat 包以构造 package-private 的 ChatEvent 记
 * 录）：ToolStarted / ToolFinished / SourcesAvailable 三类事件的有
 * 效载荷映射，以及心跳启用配置下调度器创建与完成即停。
 *
 * <h2>Batch 897：这个文件原来证明不了它标题里写的事</h2>
 *
 * 三个用例的名字分别叫 {@code toolStartedEventIsMappedToToolPayload}、
 * {@code toolFinishedEventIsMappedToResultPayload} 和
 * {@code sourcesAvailableEventIsMappedToSourcesPayload}，类注释也写着
 * "三类事件的**有效载荷映射**"。而三个用例的断言是同一句
 * {@code assertNotNull(emitter)}：emitter 由 {@code SseEmitters.create()}
 * 无条件造出，任何一条事件映射分支走没走、有没有走错，它都看不见。
 * 把 {@code sendChatEvent} 里的 {@code tool_start} 改成 {@code tool_startx}，
 * 这三个用例照样全绿——**而它们正是唯一以"载荷映射"为名的用例**。
 *
 * <p>修法不是再加一句 {@code verify}，是让事件真的能读出来。
 * {@link SseEmitter} 在没有 handler 时把 send 暂存起来，而 {@code initialize}
 * 是包级私有、测试拿不到的；所以这里改用 standalone MockMvc 走
 * {@code asyncDispatch}，直接读响应体——和
 * {@code RagControllerIntegrationTest#stream_emitsContentThenToolEventsThenDone}
 * 用的是同一套路，只是这里不需要 Spring 上下文，controller 直接 new。
 * <p>事件名与载荷字段来自 {@code RagChatController#sendChatEvent}：
 * {@code tool_start} / {@code tool_result} / {@code sources}。
 */
class RagChatControllerStreamEventTypesTest {

    private RagChatService ragChatService;
    private RagChatController controller;
    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ragChatService = mock(RagChatService.class);
        var scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(RetrievalScope.unscoped());
        RagSseProperties sse = new RagSseProperties();
        sse.setHeartbeatIntervalSeconds(1);
        controller = new RagChatController(
                ragChatService,
                mock(RagChatHistoryRepository.class),
                mock(ChatExportService.class),
                sse,
                scopeResolver,
                mock(AuditLogService.class));
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .build();
    }

    /** 跑一次 stream，读回完整的 SSE 响应体。 */
    private String streamBody() throws Exception {
        MvcResult started = mockMvc.perform(post("/rag/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "问题", "sessionId": "session-1"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(started)).andReturn();
        return new String(
                completed.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
    }

    private static void assertContains(String body, String fragment) {
        assertTrue(
                body.contains(fragment),
                () -> "SSE body does not carry " + fragment + ":\n" + body);
    }

    /**
     * 事件名，按帧解析，不按子串找。
     *
     * <p>第一版这里写的是 {@code body.contains("event:tool_start")}，
     * 反向对照当场把它抓了出来：把生产代码里的事件名改成 {@code tool_startX}，
     * {@code "event:tool_startX".contains("event:tool_start")} 成立，
     * **用例照样绿**。载荷字段之所以没这个毛病，是因为断言里带了收尾引号
     * （{@code "\"tool\":\"knowledge\""}）；{@code event:} 那一行不带，
     * 所以必须解析。
     */
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
        assertTrue(
                names.contains(eventName),
                () -> "no " + eventName + " event in " + names + ":\n" + body);
    }

    @Test
    void toolStartedEventIsMappedToToolPayload() throws Exception {
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.just(
                        new ChatEvent.ToolStarted(
                                "call-1", "knowledge", "膝盖训练"),
                        new ChatEvent.Completed(
                                "trace-1", "session-1", null, null,
                                ChatMode.PLAIN, Map.of(), "STOP",
                                List.of(), Map.of())));

        String body = streamBody();

        // 事件名、工具名、调用 id 与 query——四个字段都是这条分支独有的，
        // 少了哪一个都说明映射被改动过而测试不会知道。
        assertEmitted(body, "tool_start");
        assertContains(body, "\"tool\":\"knowledge\"");
        assertContains(body, "\"toolCallId\":\"call-1\"");
        assertContains(body, "\"query\":\"膝盖训练\"");
        verify(ragChatService).chatEvents(
                any(ChatRequest.class), any(), isNull());
    }

    @Test
    void toolFinishedEventIsMappedToResultPayload() throws Exception {
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.just(
                        new ChatEvent.ToolFinished(
                                "call-1", "knowledge", 5, 120),
                        new ChatEvent.Completed(
                                "trace-1", "session-1", null, null,
                                ChatMode.PLAIN, Map.of(), "STOP",
                                List.of(), Map.of())));

        String body = streamBody();

        assertEmitted(body, "tool_result");
        assertContains(body, "\"tool\":\"knowledge\"");
        assertContains(body, "\"resultCount\":5");
        assertContains(body, "\"elapsedMs\":120");
        assertContains(body, "\"toolCallId\":\"call-1\"");
    }

    @Test
    void sourcesAvailableEventIsMappedToSourcesPayload() throws Exception {
        com.springairag.api.dto.ChatSource source =
                new com.springairag.api.dto.ChatSource();
        source.setDocumentId("41");
        source.setTitle("膝盖训练指南");
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.just(
                        new ChatEvent.SourcesAvailable(
                                "session-1", List.of(source)),
                        new ChatEvent.Completed(
                                "trace-1", "session-1", null, null,
                                ChatMode.PLAIN, Map.of(), "STOP",
                                List.of(), Map.of())));

        String body = streamBody();

        assertEmitted(body, "sources");
        assertContains(body, "\"sessionId\":\"session-1\"");
        assertContains(body, "\"documentId\":\"41\"");
        assertContains(body, "膝盖训练指南");
    }

    @Test
    void everyMappedEventIsNamedByTheControllerNotByTheTest() throws Exception {
        // 阳性对照：把上面三条分支汇进一次流，断言每一种事件名都真的出现过。
        // 没有这一条，三个用例可能因为共享同一个错误的名字而一起绿。
        com.springairag.api.dto.ChatSource source =
                new com.springairag.api.dto.ChatSource();
        source.setDocumentId("41");
        when(ragChatService.chatEvents(
                any(ChatRequest.class), any(), isNull()))
                .thenReturn(Flux.just(
                        new ChatEvent.ContentDelta("已找到"),
                        new ChatEvent.ToolStarted("call-1", "knowledge", "膝盖"),
                        new ChatEvent.ToolFinished("call-1", "knowledge", 1, 12),
                        new ChatEvent.SourcesAvailable(
                                "session-1", List.of(source)),
                        new ChatEvent.Completed(
                                "trace-1", "session-1", "m", "m",
                                ChatMode.AGENT, Map.of(), "STOP", List.of(),
                                Map.of())));

        String body = streamBody();

        assertEquals(
                List.of("content", "tool_start", "tool_result", "sources", "done"),
                eventNames(body),
                () -> "unexpected event sequence in:\n" + body);
        assertNotNull(controller);
    }
}
