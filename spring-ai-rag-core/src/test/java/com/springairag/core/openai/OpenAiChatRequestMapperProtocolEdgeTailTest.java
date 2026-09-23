package com.springairag.core.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagOpenAiCompatibilityProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.retrieval.RetrievalFilters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * OpenAiChatRequestMapper 校验与快照边缘（Batch 588，JaCoCo 驱动）：
 * PLAIN 直通、空白 memory 忽略、快照字段缺省归一（declaredModel、
 * domainId、documentType）、retrievalOptions/effectiveScope 缺失拒绝、
 * 请求级空值组合、消息级 name/tool_calls 拒绝、总量超限、内容形状
 * 与空白文本拒绝。
 */
class OpenAiChatRequestMapperProtocolEdgeTailTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OpenAiChatRequestMapper mapper;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        properties.getOpenAiCompatibility().getModels()
                .put("rag-default",
                        new RagOpenAiCompatibilityProperties.ModelAlias());
        mapper = new OpenAiChatRequestMapper(
                new OpenAiModelAliasRegistry(properties),
                mock(OpenAiRequestRetrievalScopeAdapter.class),
                properties);
    }

    private OpenAiChatCompletionRequest read(String json) throws Exception {
        return objectMapper.readValue(json, OpenAiChatCompletionRequest.class);
    }

    @Test
    void plainModeWithoutRetrievalFieldsPassesValidation() throws Exception {
        var declaration = mapper.validateDeclaration(read("""
                {
                  "model": "rag-default",
                  "n": 1,
                  "rag": {"mode": "PLAIN"},
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """));

        assertEquals("hello", declaration.latestUser());
        assertEquals(RetrievalFilters.none(), declaration.filters());
    }

    @Test
    void nullModelIsRejectedLikeBlankModel() {
        OpenAiChatCompletionRequest request = new OpenAiChatCompletionRequest();
        request.setModel(null);
        OpenAiChatCompletionRequest.Message message =
                new OpenAiChatCompletionRequest.Message();
        message.setRole("user");
        message.setContent(objectNode("hello"));
        request.setMessages(List.of(message));

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request));

        assertEquals("model", error.getParam());
        assertEquals("missing_required_parameter", error.getCode());
    }

    private com.fasterxml.jackson.databind.JsonNode objectNode(String text) {
        try {
            return objectMapper.readTree(
                    objectMapper.writeValueAsBytes(text));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void blankMemoryDeclarationIsIgnored() throws Exception {
        var declaration = mapper.validateDeclaration(read("""
                {
                  "model": "rag-default",
                  "rag": {"memory": "   "},
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """));

        assertEquals("hello", declaration.latestUser());
    }

    @Test
    void blankModelStringIsRejected() throws Exception {
        OpenAiChatCompletionRequest request = read("""
                {
                  "model": "rag-default",
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """);
        request.setModel("   ");

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request));

        assertEquals("model", error.getParam());
    }

    @Test
    void nullMessagesListIsRejected() throws Exception {
        OpenAiChatCompletionRequest request = read("""
                {
                  "model": "rag-default",
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """);
        request.setMessages(null);

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request));

        assertEquals("messages", error.getParam());
        assertEquals("missing_required_parameter", error.getCode());
    }

    @Test
    void messageWithNameFieldIsRejected() throws Exception {
        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(read("""
                {
                  "model": "rag-default",
                  "messages": [
                    {"role": "user", "content": "hello", "name": "n"}
                  ]
                }
                """)));

        assertEquals("messages[0]", error.getParam());
        assertEquals("unsupported_message_type", error.getCode());
    }

    @Test
    void messageWithToolCallsIsRejected() throws Exception {
        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(read("""
                {
                  "model": "rag-default",
                  "messages": [
                    {"role": "user", "content": "hello",
                     "tool_calls": [{"id": "1"}]}
                  ]
                }
                """)));

        assertEquals("unsupported_message_type", error.getCode());
    }

    @Test
    void messageWithFunctionCallIsRejected() throws Exception {
        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(read("""
                {
                  "model": "rag-default",
                  "messages": [
                    {"role": "user", "content": "hello",
                     "function_call": {"name": "search"}}
                  ]
                }
                """)));

        assertEquals("unsupported_message_type", error.getCode());
    }

    @Test
    void blankRoleIsRejectedLikeMissingRole() {
        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(read("""
                {
                  "model": "rag-default",
                  "messages": [{"role": "  ", "content": "hello"}]
                }
                """)));

        assertEquals("messages[0]", error.getParam());
    }

    @Test
    void nullContentIsRejected() {
        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(read("""
                {
                  "model": "rag-default",
                  "messages": [{"role": "user", "content": null}]
                }
                """)));

        assertEquals("messages[0]", error.getParam());
    }

    @Test
    void blankTextContentIsRejected() {
        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(read("""
                {
                  "model": "rag-default",
                  "messages": [{"role": "user", "content": "   "}]
                }
                """)));

        assertEquals("messages[0]", error.getParam());
    }

    @Test
    void contentPartsWithoutTextShapeAreRejected() {
        for (String parts : new String[] {
                "[\"plain\"]",
                "[{\"text\": \"x\"}]",
                "[{\"type\": \"text\", \"text\": 12}]",
                "[{\"type\": \"text\", \"text\": \"x\", \"extra\": 1}]"}) {
            String json = """
                    {
                      "model": "rag-default",
                      "messages": [
                        {"role": "user", "content": %s}
                      ]
                    }
                    """.formatted(parts);
            OpenAiProtocolException error = assertThrows(
                    OpenAiProtocolException.class,
                    () -> mapper.validateDeclaration(read(json)),
                    "parts=" + parts);
            assertEquals("messages[0]", error.getParam(), "parts=" + parts);
        }
    }

    @Test
    void totalContentAboveLimitIsRejected() throws Exception {
        OpenAiChatCompletionRequest request = read("""
                {
                  "model": "rag-default",
                  "messages": [
                    {"role": "system", "content": "policy"},
                    {"role": "user", "content": "hello"}
                  ]
                }
                """);
        OpenAiChatCompletionRequest.Message oversized =
                new OpenAiChatCompletionRequest.Message();
        oversized.setRole("user");
        oversized.setContent(objectMapper.readTree(
                objectMapper.writeValueAsBytes("a".repeat(1_000_001))));
        request.setMessages(List.of(
                request.getMessages().getFirst(), oversized));

        OpenAiProtocolException error = assertThrows(
                OpenAiProtocolException.class,
                () -> mapper.validateDeclaration(request));

        assertEquals("messages", error.getParam());
        assertEquals("request_too_large", error.getCode());
    }

    @Test
    void snapshotWithoutDeclaredModelIdentifierIsRejected() {
        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        validRequest(), new MockHttpServletRequest(),
                        "session-1", validSnapshot()
                                .replace("\"declaredModelIdentifier\":\"m\",", "")));

        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void snapshotWithBlankDomainIdNormalizesToNull() throws Exception {
        var mapped = mapper.mapFromExecutionSnapshot(
                validRequest(),
                principalRequest(),
                "session-1",
                validSnapshot().replace("\"domainId\":\"domain-1\"",
                        "\"domainId\":\" \""));

        assertNull(mapped.command().domainId());
    }

    @Test
    void snapshotWithoutDocumentTypeKeepsNullDocumentType()
            throws Exception {
        var mapped = mapper.mapFromExecutionSnapshot(
                validRequest(),
                principalRequest(),
                "session-1",
                validSnapshot().replace("\"documentType\":\"pdf\",", ""));

        assertNull(mapped.command().retrievalScope().documentType());
        assertEquals("m", mapped.modelAlias());
    }

    @Test
    void snapshotWithMissingRetrievalOptionsIsRejected() {
        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        validRequest(), new MockHttpServletRequest(),
                        "session-1", validSnapshot()
                                .replaceAll("(?s)\"retrievalOptions\":\\{.*?\\},", "")));

        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void snapshotWithMissingEffectiveScopeIsRejected() {
        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        validRequest(), new MockHttpServletRequest(),
                        "session-1", validSnapshot()
                                .replaceAll("(?s)\"effectiveScope\":\\{.*?\\}", "")));

        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void snapshotWithEmptyRetrievalOptionsObjectIsRejected() {
        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        validRequest(), new MockHttpServletRequest(),
                        "session-1", validSnapshot()
                                .replaceAll("(?s)\"retrievalOptions\":\\{.*?\\}",
                                        "\"retrievalOptions\":{}")));

        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void snapshotWithNonObjectEffectiveScopeIsRejected() {
        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        validRequest(), new MockHttpServletRequest(),
                        "session-1", validSnapshot()
                                .replaceAll("(?s)\"effectiveScope\":\\{.*?\\}",
                                        "\"effectiveScope\":[]")));

        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void snapshotHappyPathMapsWeightsAndMatchNone() throws Exception {
        var mapped = mapper.mapFromExecutionSnapshot(
                validRequest(),
                principalRequest(),
                "session-1",
                validSnapshot());

        assertEquals("pdf", mapped.command().retrievalScope().documentType());
        assertEquals("domain-1", mapped.command().domainId());
        assertEquals(0.6, mapped.command().retrievalOptions().vectorWeight());
        assertTrue(mapped.command().retrievalScope().matchNone() == false);
        assertEquals("db:1", mapped.command().principal().id());
        assertEquals("hello", mapped.command().message());
    }

    private OpenAiChatCompletionRequest validRequest() throws Exception {
        return read("""
                {
                  "model": "rag-default",
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """);
    }

    private MockHttpServletRequest principalRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_KEY_ATTRIBUTE,
                "1");
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_PRINCIPAL_TYPE,
                com.springairag.core.filter.ApiKeyAuthFilter
                        .PRINCIPAL_DATABASE_API_KEY);
        return request;
    }

    private String validSnapshot() {
        return """
                {"executionSnapshotVersion":1,"mode":"KNOWLEDGE",
                 "memoryMode":"STATELESS","declaredModelIdentifier":"m",
                 "resolvedCandidates":["acme/m1"],
                 "domainId":"domain-1",
                 "retrievalOptions":{"maxResults":5,"minScore":0.3,
                   "useHybridSearch":true,"useRerank":false,
                   "vectorWeight":0.6,"fulltextWeight":0.4},
                 "effectiveScope":{"collectionFilter":"NONE",
                   "collectionIds":[],"documentIds":[],
                   "documentType":"pdf","matchNone":false}}""";
    }
}
