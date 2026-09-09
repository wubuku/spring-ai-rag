package com.springairag.core.openai;

import com.springairag.api.openai.OpenAiChatCompletionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 请求校验分支：必填参数、消息数量、n 限制、rag 未知字段
 * （顶层/filters/scope）与合法过滤器经校验器解析。
 */
class OpenAiChatRequestMapperValidationTest {

    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private OpenAiRequestRetrievalScopeAdapter scopeAdapter;
    private OpenAiChatRequestMapper mapper;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        scopeAdapter = mock(OpenAiRequestRetrievalScopeAdapter.class);
        com.springairag.core.config.RagProperties properties =
                new com.springairag.core.config.RagProperties();
        com.springairag.core.config.RagOpenAiCompatibilityProperties.ModelAlias alias =
                new com.springairag.core.config.RagOpenAiCompatibilityProperties.ModelAlias();
        properties.getOpenAiCompatibility().getModels().put("rag-default", alias);
        mapper = new OpenAiChatRequestMapper(
                new OpenAiModelAliasRegistry(properties),
                scopeAdapter,
                properties);
        when(scopeAdapter.resolve(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.springairag.core.retrieval.RetrievalScope.unscoped());
    }

    private OpenAiChatCompletionRequest parse(String json) throws Exception {
        return objectMapper.readValue(json, OpenAiChatCompletionRequest.class);
    }

    @Test
    void rejectsBlankModel() throws Exception {
        OpenAiChatCompletionRequest request = parse("""
                {
                  "model": " ",
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request));
        assertEquals("model", error.getParam());
        assertEquals("missing_required_parameter", error.getCode());
    }

    @Test
    void rejectsEmptyMessageList() throws Exception {
        OpenAiChatCompletionRequest request = parse("""
                {
                  "model": "rag-default",
                  "messages": []
                }
                """);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request));
        assertEquals("messages", error.getParam());
        assertEquals("missing_required_parameter", error.getCode());
    }

    @Test
    void rejectsMoreThanMaxMessages() throws Exception {
        String messages = java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(i -> "{\"role\": \"user\", \"content\": \"m" + i + "\"}")
                .collect(Collectors.joining(","));
        OpenAiChatCompletionRequest request = parse("""
                {
                  "model": "rag-default",
                  "messages": [%s]
                }
                """.formatted(messages));

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request));
        assertEquals("messages", error.getParam());
        assertEquals("invalid_value", error.getCode());
    }

    @Test
    void rejectsSampleCountAboveOne() throws Exception {
        OpenAiChatCompletionRequest request = parse("""
                {
                  "model": "rag-default",
                  "n": 2,
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request));
        assertEquals("n", error.getParam());
        assertEquals("unsupported_parameter", error.getCode());
    }

    @Test
    void rejectsUnknownRagFields() throws Exception {
        OpenAiChatCompletionRequest request = parse("""
                {
                  "model": "rag-default",
                  "rag": {
                    "unknown_field": {"deep": true}
                  },
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request));
        assertEquals("rag", error.getParam());
        assertEquals("unsupported_parameter", error.getCode());
        assertTrue(error.getMessage().contains("unknown_field"));
        verifyNoInteractions(scopeAdapter);
    }

    @Test
    void rejectsUnknownRagFilterFields() throws Exception {
        OpenAiChatCompletionRequest request = parse("""
                {
                  "model": "rag-default",
                  "rag": {
                    "filters": {
                      "metadata_contains": {"color": "red"},
                      "payload_contains": {"fabric": "cloth"},
                      "bonus": 1
                    }
                  },
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request));
        assertEquals("rag.filters", error.getParam());
        assertEquals("unsupported_parameter", error.getCode());
    }

    @Test
    void rejectsUnknownRagScopeFields() throws Exception {
        OpenAiChatCompletionRequest request = parse("""
                {
                  "model": "rag-default",
                  "rag": {
                    "scope": {
                      "mode": "ANY_COLLECTION",
                      "mystery": true
                    }
                  },
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request));
        assertEquals("rag.scope", error.getParam());
        assertEquals("unsupported_parameter", error.getCode());
    }

    @Test
    void resolvesFiltersThroughTheValidator() throws Exception {
        OpenAiChatCompletionRequest request = parse("""
                {
                  "model": "rag-default",
                  "rag": {
                    "filters": {
                      "metadata_contains": {"color": "red"},
                      "payload_contains": {"fabric": "cloth"}
                    }
                  },
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """);

        OpenAiChatRequestMapper.Declaration declaration =
                mapper.validateDeclaration(request);

        assertNotNull(declaration.filters());
        // JsonbContainmentFilter 规范化 JSON：键序可能变化，断言包含内容。
        String metadataJson =
                declaration.filters().metadataContains().canonicalJson();
        assertTrue(metadataJson.contains("\"color\"")
                && metadataJson.contains("\"red\""));
        // record 组件名即访问器：metadata/payload。
        String payloadJson =
                declaration.filters().payloadContainsAll().getFirst().canonicalJson();
        assertTrue(payloadJson.contains("\"fabric\"")
                && payloadJson.contains("\"cloth\""));
    }
}
