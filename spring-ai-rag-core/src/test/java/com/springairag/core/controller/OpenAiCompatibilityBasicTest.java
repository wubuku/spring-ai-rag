package com.springairag.core.controller;

import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.api.openai.OpenAiModelResponse;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.openai.OpenAiChatRequestMapper;
import com.springairag.core.openai.OpenAiModelAliasRegistry;
import com.springairag.core.openai.OpenAiModelAliasRegistry.AliasDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenAI 兼容端点的非流式单元语义：模型清单/单个模型映射、
 * 非键控 JSON 完成路径（映射命令执行、OpenAI 信封响应、缓存头）。
 */
class OpenAiCompatibilityBasicTest {

    private OpenAiModelAliasRegistry aliasRegistry;
    private OpenAiChatRequestMapper requestMapper;
    private ChatExecutionService executionService;
    private OpenAiCompatibilityController controller;

    private static final ChatPrincipal PRINCIPAL =
            new ChatPrincipal("db:1", "DATABASE_API_KEY", false);

    @BeforeEach
    void setUp() {
        aliasRegistry = mock(OpenAiModelAliasRegistry.class);
        requestMapper = mock(OpenAiChatRequestMapper.class);
        executionService = mock(ChatExecutionService.class);
        controller = new OpenAiCompatibilityController(
                aliasRegistry,
                requestMapper,
                executionService,
                new ObjectMapper());
    }

    private AliasDefinition alias(String name) {
        return new AliasDefinition(
                name, List.of("vendor/" + name), ChatMode.KNOWLEDGE,
                null, true, true);
    }

    private ChatCommand command() {
        return new ChatCommand(
                "hello", "session-1", PRINCIPAL, null,
                ChatMode.KNOWLEDGE, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private ChatExecutionResult result() {
        return new ChatExecutionResult(
                "answer text",
                "session-1",
                "trace-1",
                null,
                null,
                ChatMode.KNOWLEDGE,
                List.of(),
                Map.of(),
                "STOP",
                List.of(),
                Map.of());
    }

    private OpenAiChatCompletionRequest completionRequest() {
        OpenAiChatCompletionRequest request = new OpenAiChatCompletionRequest();
        request.setModel("gpt-compatible");
        request.setStream(false);
        return request;
    }

    @Test
    void listModelsWrapsAliasesInAnOpenAiListEnvelope() {
        when(aliasRegistry.list()).thenReturn(List.of(alias("a"), alias("b")));

        OpenAiModelResponse.ListEnvelope envelope = controller.listModels();

        assertEquals("list", envelope.object());
        assertEquals(2, envelope.data().size());
        assertEquals("a", envelope.data().get(0).id());
    }

    @Test
    void getModelMapsAliasDefinitionById() {
        when(aliasRegistry.require("a")).thenReturn(alias("a"));

        OpenAiModelResponse.Model model = controller.getModel("a");

        assertEquals("a", model.id());
    }

    @Test
    void nonKeyedJsonCompletionExecutesCommandAndRespondsJson() {
        ChatCommand command = command();
        when(requestMapper.map(any(), any()))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "gpt-compatible", false, command));
        when(executionService.execute(same(command))).thenReturn(result());

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(completionRequest(), null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON,
                response.getHeaders().getContentType());
        assertNotNull(response.getBody());
        assertInstanceOf(ResponseBodyEmitter.class, response.getBody());
        verify(executionService).execute(same(command));
    }

    @Test
    void diagnosticsDisabledCommandPassesThroughUnwrapped() {
        RetrievalDiagnosticsService diagnostics =
                mock(RetrievalDiagnosticsService.class);
        when(diagnostics.isEnabled()).thenReturn(false);
        controller.setDiagnosticsService(diagnostics);
        ChatCommand command = command();
        when(requestMapper.map(any(), any()))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "gpt-compatible", false, command));
        when(executionService.execute(any())).thenReturn(result());

        controller.chatCompletions(completionRequest(), null);

        // 诊断关闭时命令保持原样（同一实例，不附加 trace session）。
        verify(executionService).execute(same(command));
    }

    @Test
    void completionResponseCarriesChatResponseAnswerThroughBuilder() {
        ChatCommand command = command();
        when(requestMapper.map(any(), any()))
                .thenReturn(new OpenAiChatRequestMapper.MappedRequest(
                        "gpt-compatible", false, command));
        ChatExecutionResult withModel = new ChatExecutionResult(
                "answer", "session-1", "trace-1",
                "gpt-compatible", "gpt-compatible",
                ChatMode.KNOWLEDGE, List.of(), Map.of(), "STOP",
                List.of(), Map.of());
        when(executionService.execute(any())).thenReturn(withModel);

        ResponseEntity<ResponseBodyEmitter> response =
                controller.chatCompletions(completionRequest(), null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        // 生成器内部消费原生 ChatResponse；此处锁定不抛异常且类型正确。
        assertEquals(ResponseBodyEmitter.class, response.getBody().getClass());
    }
}
