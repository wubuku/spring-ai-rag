package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.openai.OpenAiChatRequestMapper;
import com.springairag.core.openai.OpenAiModelAliasRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * OpenAI 兼容流式响应生命周期长尾（Batch 647，JaCoCo 驱动）：
 * <b>同步完成</b>的流——全部事件在 {@code subscribe} 返回前就发完，
 * 因此 {@code emitter.complete()} 先于 {@code subscription.set(active)}
 * 发生，完成回调里的 dispose 看不到 active，订阅返回后靠 terminated
 * 短路兜底。
 *
 * <h2>Batch 948：这两个用例原来证明不了自己标题里写的事</h2>
 *
 * 原来两条用例都用反射调私有方法 {@code streamResponse}，唯一的断言是
 * {@code assertNotNull(emitter)}。而 {@code streamResponse} 无条件
 * {@code new SseEmitter(0L)} 就把它 return 了——把整段事件映射、角色块、
 * {@code [DONE]} 收尾全删掉，这两条用例照样绿。用例注释里"terminated
 * 短路再次 dispose"更是无从谈起：反射拿到 emitter 之后没有任何东西再去
 * 碰它。
 *
 * <p>修法与 {@code RagChatControllerStreamEventTypesTest}（Batch 897）、
 * {@code RagChatControllerSseLifecycleTailTest}（Batch 945）一致：改走
 * standalone MockMvc + {@code asyncDispatch}，直接读响应体。
 *
 * <h3>仍然不可观测的部分（不再声称覆盖）</h3>
 *
 * {@code streamResponse} 末尾的 {@code if (terminated.get()) active.dispose();}
 * 在响应体层面没有可观测差异——Flux 已经终止，dispose 一个已终止的
 * Disposable 不改变任何输出。本文件因此<b>不</b>声称覆盖该分支；
 * 也不要把它算进覆盖率的理由里。是否删除该分支见 Batch 948 的待拍板项。
 *
 * <h2>Batch 948 还抓出一个真缺陷</h2>
 *
 * 铺上 HTTP 通道之后才看得见：{@code SseEmitter.event().data(String)}
 * 的载荷由 {@code StringHttpMessageConverter} 按 ISO-8859-1 写出，于是
 * 本端点的流式响应把<b>所有非 ASCII 字符变成 {@code ?}</b>。中文答案、
 * 模型别名、上游错误消息全丢，而在此之前没有任何用例能发现——它们断言
 * 的是 {@code assertNotNull(emitter)}。见
 * {@link #nonAsciiAnswerAndModelAliasSurviveAsUtf8()}。
 */
class OpenAiCompatibilitySseLifecycleTailTest {

    private ChatExecutionService executionService;
    private OpenAiChatRequestMapper requestMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        executionService = mock(ChatExecutionService.class);
        requestMapper = mock(OpenAiChatRequestMapper.class);
        // 不注入 ChatTurnOperationService：prepareTurn 的 null 分支直接返回
        // null，走的是无幂等键的普通流式路径，与本文件要验的形态一致。
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new OpenAiCompatibilityController(
                                mock(OpenAiModelAliasRegistry.class),
                                requestMapper,
                                executionService,
                                new ObjectMapper()))
                .build();
    }

    private ChatCommand command() {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                ChatMode.PLAIN, MemoryMode.SERVER, null, null,
                null, null, Map.of(),
                List.of(), List.of(), null, null, null);
    }

    private void streamWith(ChatCommand command, Flux<ChatEvent> events) {
        when(requestMapper.map(any(), any())).thenReturn(
                new OpenAiChatRequestMapper.MappedRequest("gpt-x", true, command));
        when(executionService.stream(command)).thenReturn(events);
    }

    /** 跑一次 /v1/chat/completions 的流式请求，读回完整 SSE 响应体。 */
    private String streamBody() throws Exception {
        MvcResult started = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-x",
                                  "stream": true,
                                  "messages": [
                                    {"role": "user", "content": "问题"}
                                  ]
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        // MockMvc async 通道下 dispatch 之前必须先把 async 结果取出来，
        // 否则会撞 timeToWait=0（Batch 945 的教训）。
        started.getAsyncResult(10_000);
        MvcResult completed = mockMvc.perform(asyncDispatch(started)).andReturn();
        return new String(
                completed.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
    }

    /** 按帧取 data 载荷，不用子串 find（"data:[DONE]" 这种含前缀的片段尤其不能）。 */
    private static List<String> dataFrames(String body) {
        List<String> frames = new ArrayList<>();
        for (String frame : body.split("\n\n")) {
            for (String line : frame.lines().toList()) {
                if (line.startsWith("data:")) {
                    frames.add(line.substring("data:".length()));
                }
            }
        }
        return frames;
    }

    @Test
    void emptyStreamEmitsRoleChunkThenDoneSentinel() throws Exception {
        ChatCommand command = command();
        streamWith(command, Flux.empty());

        String body = streamBody();

        List<String> frames = dataFrames(body);
        // role 块与 [DONE] 各一帧，中间不该凭空多出内容块。
        assertEquals(2, frames.size(), () -> "帧数不对：" + body);
        assertTrue(frames.get(0).contains("\"role\":\"assistant\""),
                () -> "首帧不是 role 块：" + body);
        assertEquals("[DONE]", frames.get(1), () -> "末帧不是 [DONE]：" + body);
    }

    @Test
    void synchronousStreamWithEventsEmitsRoleContentFinishThenDone()
            throws Exception {
        // 全部事件同步发射：订阅在发完最后一项前就返回了。
        // 内容用 ASCII：本用例要验的是事件映射与帧序，不是字符集。SSE 响应
        // 的 charset 另有实测结论，见类注释。
        ChatCommand command = command();
        streamWith(command, Flux.just(
                new ChatEvent.ContentDelta("hello"),
                new ChatEvent.Completed("trace", "session-1",
                        null, null, ChatMode.PLAIN,
                        Map.of(), "STOP", List.of(), Map.of())));

        String body = streamBody();

        List<String> frames = dataFrames(body);
        assertEquals(4, frames.size(), () -> "帧数不对：" + body);
        assertTrue(frames.get(0).contains("\"role\":\"assistant\""),
                () -> "首帧不是 role 块：" + body);
        assertTrue(frames.get(1).contains("\"content\":\"hello\""),
                () -> "第二帧不是内容块：" + body);
        assertTrue(frames.get(2).contains("\"finish_reason\":\"stop\""),
                () -> "第三帧不是结束块：" + body);
        assertEquals("[DONE]", frames.get(3), () -> "末帧不是 [DONE]：" + body);
    }

    /**
     * Batch 948：这条测的是一个真缺陷，不是补覆盖率。
     *
     * <p>{@code SseEmitter.event().data(String)} 的载荷走
     * {@code StringHttpMessageConverter}，默认字符集是 ISO-8859-1，而
     * {@code text/event-stream} 不带 charset。后果是本端点的流式响应把
     * **所有非 ASCII 字符写成 {@code ?}**——中文答案、模型别名、上游错误
     * 消息全丢。修之前实测：响应体里一个 &gt;0x7F 的字节都没有，
     * {@code "模型-alias"} 变成 {@code "??"-alias}、{@code "中文片段"} 变成
     * {@code "??"}。
     *
     * <p>{@code RagChatController} 的 SSE 不中招，因为它送 Map 对象、走
     * Jackson；只有这个兼容入口送预序列化的字符串。
     *
     * <p>为什么上面两条用例用 ASCII：它们要验的是事件映射与帧序，不是
     * 字符集；而这一条专门用非 ASCII，并且自带字面量自检——否则修好之后
     * {@code contains} 反而会在"body 和字面量一起被改坏"时成立。
     */
    @Test
    void nonAsciiAnswerAndModelAliasSurviveAsUtf8() throws Exception {
        // 自检：断言里这个中文字面量必须就是这几个码点本身。
        // 万一将来有编码问题把它变成 "??"，body.contains(字面量) 会在
        // body 同样变成 "??" 时成立——用例就此变绿。把它钉死。
        assertEquals("中文片段", new String(new char[] {
                0x4E2D, 0x6587, 0x7247, 0x6BB5}),
                "本用例的中文字面量被改动了");
        assertEquals("模型-alias", new String(new char[] {
                0x6A21, 0x578B}) + "-alias",
                "本用例的中文字面量被改动了");

        ChatCommand command = command();
        when(requestMapper.map(any(), any())).thenReturn(
                new OpenAiChatRequestMapper.MappedRequest(
                        "模型-alias", true, command));
        when(executionService.stream(command)).thenReturn(
                Flux.just(new ChatEvent.ContentDelta("中文片段")));

        String body = streamBody();

        assertTrue(body.contains("\"model\":\"模型-alias\""),
                () -> "模型别名在响应体里不是 UTF-8：\n" + body);
        assertTrue(body.contains("\"content\":\"中文片段\""),
                () -> "中文内容在响应体里不是 UTF-8：\n" + body);
    }
}
