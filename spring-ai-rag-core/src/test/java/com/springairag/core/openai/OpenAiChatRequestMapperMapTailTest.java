package com.springairag.core.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * OpenAiChatRequestMapper 编排长尾（Batch 431）：map 的 PLAIN
 * filters 拒绝、mapFromExecutionSnapshot 的版本/枚举/声明模型
 * 字段校验与字段映射、stream 标志透传。
 */
class OpenAiChatRequestMapperMapTailTest {

    private ObjectMapper mapper = new ObjectMapper();

    private OpenAiChatRequestMapper newMapper() {
        return new OpenAiChatRequestMapper(
                mock(OpenAiModelAliasRegistry.class),
                mock(OpenAiRequestRetrievalScopeAdapter.class),
                new RagProperties());
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/v1/chat/completions");
    }

    private com.fasterxml.jackson.databind.JsonNode jsonText(String text) {
        try {
            return mapper.readTree(mapper.writeValueAsBytes(text));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private OpenAiChatCompletionRequest validRequest() {
        OpenAiChatCompletionRequest request = new OpenAiChatCompletionRequest();
        request.setModel("m");
        OpenAiChatCompletionRequest.Message message =
                new OpenAiChatCompletionRequest.Message();
        message.setRole("user");
        message.setContent(jsonText("question"));
        request.setMessages(List.of(message));
        return request;
    }

    @Test
    void mapRejectsPlainModeWithFiltersBeforeScopeResolution() {
        OpenAiChatRequestMapper mapper = newMapper();
        OpenAiChatCompletionRequest request = validRequest();
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        rag.setMode(com.springairag.api.enums.ChatMode.PLAIN);
        OpenAiChatCompletionRequest.Filters filters =
                new OpenAiChatCompletionRequest.Filters();
        filters.setMetadataContains(jsonText("{}").deepCopy());
        rag.setFilters(filters);
        request.setRag(rag);

        // PLAIN + filters 属协议错误，先于别名/scope 协作方抛出。
        assertThrows(OpenAiProtocolException.class,
                () -> mapper.map(request, request()));
    }

    @Test
    void mapFromExecutionSnapshotRejectsUnknownVersion() {
        OpenAiChatRequestMapper mapper = newMapper();
        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        validRequest(), request(), "session-1",
                        "{\"executionSnapshotVersion\":2}"));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void mapFromExecutionSnapshotRejectsUnknownModeEnum() {
        OpenAiChatRequestMapper mapper = newMapper();
        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        validRequest(), request(), "session-1",
                        "{\"executionSnapshotVersion\":1,\"mode\":\"WIZARD\"}"));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void mapFromExecutionSnapshotRejectsMissingDeclaredModel() {
        OpenAiChatRequestMapper mapper = newMapper();
        OpenAiChatCompletionRequest request = validRequest();
        String snapshot = """
                {"executionSnapshotVersion":1,"mode":"KNOWLEDGE",
                 "memoryMode":"STATELESS","declaredModelIdentifier":" ",
                 "resolvedCandidates":["acme/m1"],
                 "retrievalOptions":{"maxResults":5,"minScore":0.3,
                   "useHybridSearch":true,"useRerank":false,
                   "vectorWeight":0.5,"fulltextWeight":0.5},
                 "effectiveScope":{"collectionFilter":"NONE",
                   "collectionIds":[],"documentIds":[],
                   "documentType":null,"matchNone":false}}""";
        RagException error = assertThrows(RagException.class,
                () -> mapper.mapFromExecutionSnapshot(
                        request, request(), "session-1", snapshot));
        assertEquals(ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                error.getErrorCodeEnum());
    }

    @Test
    void mapFromExecutionSnapshotMapsFieldsAndStreamFlag() {
        OpenAiChatRequestMapper mapper = newMapper();
        String snapshot = """
                {"executionSnapshotVersion":1,"mode":"KNOWLEDGE",
                 "memoryMode":"SERVER","declaredModelIdentifier":"alias-m",
                 "resolvedCandidates":["acme/m1"],
                 "retrievalOptions":{"maxResults":5,"minScore":0.3,
                   "useHybridSearch":true,"useRerank":true,
                   "vectorWeight":0.5,"fulltextWeight":0.5},
                 "effectiveScope":{"collectionFilter":"SELECTED",
                   "collectionIds":[7],"documentIds":[],
                   "documentType":"json-record","matchNone":false}}""";
        OpenAiChatCompletionRequest request = validRequest();
        request.setStream(true);

        OpenAiChatRequestMapper.MappedRequest mapped =
                mapper.mapFromExecutionSnapshot(
                        request, request(), "session-1", snapshot);

        assertEquals("alias-m", mapped.modelAlias());
        assertEquals(true, mapped.stream());
        assertEquals("session-1", mapped.command().sessionId());
        assertEquals(com.springairag.api.enums.ChatMode.KNOWLEDGE,
                mapped.command().mode());
        assertEquals("acme/m1", mapped.command().modelRef());
    }
}
