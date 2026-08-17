package com.springairag.core.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.chat.ChatInputMessage;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.config.RagOpenAiCompatibilityProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiChatRequestMapperTest {

    private ObjectMapper objectMapper;
    private OpenAiRequestRetrievalScopeAdapter scopeAdapter;
    private OpenAiChatRequestMapper mapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        scopeAdapter = mock(OpenAiRequestRetrievalScopeAdapter.class);
        RagProperties properties = new RagProperties();
        RagOpenAiCompatibilityProperties.ModelAlias alias =
                new RagOpenAiCompatibilityProperties.ModelAlias();
        properties.getOpenAiCompatibility().getModels()
                .put("rag-default", alias);
        mapper = new OpenAiChatRequestMapper(
                new OpenAiModelAliasRegistry(properties),
                scopeAdapter,
                properties);
        when(scopeAdapter.resolve(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(RetrievalScope.unscoped());
    }

    @Test
    void preservesFullTextMessageOrderAndUsesLatestUserAsQuery()
            throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model": "rag-default",
                  "messages": [
                    {"role": "system", "content": "Client policy"},
                    {"role": "developer", "content": "Use concise prose"},
                    {"role": "user", "content": "first question"},
                    {"role": "assistant", "content": "first answer"},
                    {"role": "user", "content": [
                      {"type": "text", "text": "find"},
                      {"type": "text", "text": "破皮沙发"}
                    ]}
                  ]
                }
                """, OpenAiChatCompletionRequest.class);

        OpenAiChatRequestMapper.MappedRequest mapped = mapper.map(
                request, new MockHttpServletRequest());

        assertEquals("find\n破皮沙发", mapped.command().message());
        assertEquals(ChatMode.KNOWLEDGE, mapped.command().mode());
        assertEquals(MemoryMode.STATELESS,
                mapped.command().memoryMode());
        assertNull(mapped.command().modelRef());
        assertEquals(List.of(
                        ChatInputMessage.Role.SYSTEM,
                        ChatInputMessage.Role.DEVELOPER,
                        ChatInputMessage.Role.USER,
                        ChatInputMessage.Role.ASSISTANT,
                        ChatInputMessage.Role.USER),
                mapped.command().inputMessages().stream()
                        .map(ChatInputMessage::role)
                        .toList());
    }

    @Test
    void rejectsMultimodalPartsInsteadOfDroppingThem() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model": "rag-default",
                  "messages": [{
                    "role": "user",
                    "content": [{"type": "image_url", "image_url": {"url": "x"}}]
                  }]
                }
                """, OpenAiChatCompletionRequest.class);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.map(request, new MockHttpServletRequest()));

        assertEquals("unsupported_message_type", error.getCode());
    }

    @Test
    void rejectsGenerationOverridesThatWouldOtherwiseBeIgnored()
            throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model": "rag-default",
                  "temperature": 0.2,
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """, OpenAiChatCompletionRequest.class);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.map(request, new MockHttpServletRequest()));

        assertEquals("temperature", error.getParam());
        assertEquals("unsupported_parameter", error.getCode());
    }
}
