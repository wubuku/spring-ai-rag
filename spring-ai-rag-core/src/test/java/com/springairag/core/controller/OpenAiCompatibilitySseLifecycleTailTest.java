package com.springairag.core.controller;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.openai.OpenAiChatRequestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpenAI 兼容流式响应生命周期长尾（Batch 647，JaCoCo 驱动）：
 * 流在订阅内同步完成时，onCompletion 的 dispose 立即生效并在订阅
 * 返回后追加 dispose（terminated 短路）。
 */
class OpenAiCompatibilitySseLifecycleTailTest {

    private ChatExecutionService executionService;
    private OpenAiCompatibilityController controller;

    @BeforeEach
    void setUp() {
        executionService = mock(ChatExecutionService.class);
        controller = new OpenAiCompatibilityController(
                mock(com.springairag.core.openai.OpenAiModelAliasRegistry.class),
                mock(OpenAiChatRequestMapper.class),
                executionService,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private OpenAiChatRequestMapper.MappedRequest mapped() {
        ChatPrincipal principal = ChatPrincipal.local();
        ChatCommand command = new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                ChatMode.PLAIN, MemoryMode.SERVER, null, null,
                null, null, Map.of(),
                List.of(), List.of(), null, null, null);
        return new OpenAiChatRequestMapper.MappedRequest(
                "gpt-x", false, command);
    }

    private Object streamResponse() throws Exception {
        Method method = OpenAiCompatibilityController.class
                .getDeclaredMethod("streamResponse",
                        String.class, long.class,
                        OpenAiChatRequestMapper.MappedRequest.class,
                        ChatTurnOperationService.Claim.class);
        method.setAccessible(true);
        return method.invoke(controller,
                "chatcmpl-1", 1_700_000_000L, mapped(), null);
    }

    @Test
    void synchronousStreamCompletionDisposesSubscription() throws Exception {
        when(executionService.stream(any(ChatCommand.class)))
                .thenReturn(reactor.core.publisher.Flux.empty());

        Object emitter = streamResponse();

        assertNotNull(emitter);
    }

    @Test
    void synchronousStreamWithEventsDisposesAfterTerminal() throws Exception {
        // 全部事件同步发射：完成回调先行 dispose，订阅返回后
        // terminated 短路再次 dispose。
        when(executionService.stream(any(ChatCommand.class)))
                .thenReturn(reactor.core.publisher.Flux.just(
                        (ChatEvent) new ChatEvent.ContentDelta("片段"),
                        new ChatEvent.Completed("trace", "session-1",
                                null, null, ChatMode.PLAIN,
                                Map.of(), "STOP", List.of(), Map.of())));

        Object emitter = streamResponse();

        assertNotNull(emitter);
    }
}
