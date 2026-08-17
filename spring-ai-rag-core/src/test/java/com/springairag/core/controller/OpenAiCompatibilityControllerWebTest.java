package com.springairag.core.controller;

import com.springairag.core.chat.ChatExecutionResult;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatEvent;
import com.springairag.core.openai.OpenAiChatRequestMapper;
import com.springairag.core.openai.OpenAiModelAliasRegistry;
import com.springairag.core.openai.OpenAiProtocolException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = OpenAiCompatibilityController.class,
        properties = {
                "rag.openai-compatibility.enabled=true",
                "rag.cors.enabled=false",
                "rag.slo.enabled=false"
        })
@Import(OpenAiCompatibilityExceptionHandler.class)
class OpenAiCompatibilityControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OpenAiModelAliasRegistry aliasRegistry;

    @MockBean
    private OpenAiChatRequestMapper requestMapper;

    @MockBean
    private ChatExecutionService executionService;

    @Test
    void modelsExposeAliasesWithoutCollectionMetadata() throws Exception {
        when(aliasRegistry.list()).thenReturn(List.of(
                new OpenAiModelAliasRegistry.AliasDefinition(
                        "rag-default",
                        List.of("internal/model"),
                        com.springairag.api.enums.ChatMode.KNOWLEDGE,
                        com.springairag.core.chat.MemoryMode.STATELESS,
                        false,
                        false)));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[0].id").value("rag-default"))
                .andExpect(jsonPath("$.data[0].owned_by")
                        .value("spring-ai-rag"))
                .andExpect(jsonPath("$.data[0].collectionKeys")
                        .doesNotExist());
    }

    @Test
    void nonStreamingCompletionUsesOpenAiEnvelope() throws Exception {
        com.springairag.core.chat.ChatCommand command =
                com.springairag.core.chat.ChatCommand.of(
                        "hello",
                        "session",
                        com.springairag.api.enums.ChatMode.PLAIN,
                        null,
                        com.springairag.core.retrieval.RetrievalScope.unscoped(),
                        new com.springairag.core.chat.RetrievalOptions(
                                5, 0.3, true, true, 0.5, 0.5),
                        Map.of());
        when(requestMapper.map(any(), any())).thenReturn(
                new OpenAiChatRequestMapper.MappedRequest(
                        "rag-default", false, command));
        when(executionService.execute(command)).thenReturn(
                new ChatExecutionResult(
                        "answer",
                        "session",
                        "trace",
                        null,
                        "internal/model",
                        com.springairag.api.enums.ChatMode.PLAIN,
                        List.of(),
                        Map.of("promptTokens", 2,
                                "completionTokens", 3,
                                "totalTokens", 5),
                        "STOP",
                        List.of(),
                        Map.of()));

        MvcResult started = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "rag-default",
                                  "messages": [
                                    {"role": "user", "content": "hello"}
                                  ]
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object")
                        .value("chat.completion"))
                .andExpect(jsonPath("$.model").value("rag-default"))
                .andExpect(jsonPath("$.choices[0].message.role")
                        .value("assistant"))
                .andExpect(jsonPath("$.choices[0].message.content")
                        .value("answer"))
                .andExpect(jsonPath("$.choices[0].finish_reason")
                        .value("stop"))
                .andExpect(jsonPath("$.usage.total_tokens").value(5));
    }

    @Test
    void protocolErrorsUseOpenAiEnvelope() throws Exception {
        when(requestMapper.map(any(), any())).thenThrow(
                OpenAiProtocolException.modelNotFound("missing"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "missing",
                                  "messages": [
                                    {"role": "user", "content": "hello"}
                                  ]
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type")
                        .value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code")
                        .value("model_not_found"));
    }

    @Test
    void streamingCompletionUsesOrderedOpenAiSseAndDoneSentinel()
            throws Exception {
        com.springairag.core.chat.ChatCommand command =
                com.springairag.core.chat.ChatCommand.of(
                        "hello",
                        "session",
                        com.springairag.api.enums.ChatMode.PLAIN,
                        null,
                        com.springairag.core.retrieval.RetrievalScope.unscoped(),
                        new com.springairag.core.chat.RetrievalOptions(
                                5, 0.3, true, true, 0.5, 0.5),
                        Map.of());
        when(requestMapper.map(any(), any())).thenReturn(
                new OpenAiChatRequestMapper.MappedRequest(
                        "rag-default", true, command));
        when(executionService.stream(command)).thenReturn(Flux.just(
                new ChatEvent.ContentDelta("hello"),
                new ChatEvent.Completed(
                        "trace",
                        "session",
                        "requested",
                        "resolved",
                        com.springairag.api.enums.ChatMode.PLAIN,
                        Map.of(),
                        "STOP",
                        List.of())));

        MvcResult started = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "rag-default",
                                  "stream": true,
                                  "messages": [
                                    {"role": "user", "content": "hello"}
                                  ]
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        String body = new String(
                completed.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);

        int roleIndex = body.indexOf("\"role\":\"assistant\"");
        int contentIndex = body.indexOf("\"content\":\"hello\"");
        int finishIndex = body.indexOf("\"finish_reason\":\"stop\"");
        int doneIndex = body.indexOf("data:[DONE]");
        assertTrue(roleIndex >= 0, body);
        assertTrue(contentIndex > roleIndex, body);
        assertTrue(finishIndex > contentIndex, body);
        assertTrue(doneIndex > finishIndex, body);
    }
}
