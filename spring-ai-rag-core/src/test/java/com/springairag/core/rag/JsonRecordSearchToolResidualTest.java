package com.springairag.core.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordSearchResponse;
import com.springairag.api.dto.JsonRecordSearchResult;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.JsonRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JsonRecordSearchTool 残余（Batch 365）：工具元数据、单参 call
 * 委托、空白入参回退空对象、超大输出整体替换为预算错误信封。
 */
class JsonRecordSearchToolResidualTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JsonRecordService service;
    private RagProperties properties;
    private JsonRecordSearchTool tool;

    @BeforeEach
    void setUp() {
        service = mock(JsonRecordService.class);
        properties = new RagProperties();
        properties.getStructuredRecords().setAgentToolEnabled(true);
        tool = new JsonRecordSearchTool(objectMapper, service, properties);
    }

    private AuthorizedRetrievalContext context(int maxToolResultCharacters) {
        return new AuthorizedRetrievalContext(
                RetrievalScope.anyAssigned(null, null),
                new RetrievalOptions(5, 0.2, true, false, 0.5, 0.5),
                new RetrievalTraceCollector(),
                "json-tool-residual",
                ChatPrincipal.local(),
                maxToolResultCharacters);
    }

    private ToolContext toolContext(AuthorizedRetrievalContext context) {
        return new ToolContext(Map.of(
                KnowledgeSearchTool.CONTEXT_KEY, context));
    }

    private JsonRecordSearchResult result(JsonNode payload) {
        return new JsonRecordSearchResult(
                11L, 7L, "records:v1", "sofa-11", "sofa", "catalog",
                "A sofa.", payload, 0.9, 0.8, 0.7, Map.of());
    }

    private JsonRecordService.DetailedSearchResult detailed(
            JsonRecordSearchResponse response) {
        List<RetrievalResult> traceResults = response.results().stream()
                .map(result -> {
                    RetrievalResult retrieval = new RetrievalResult();
                    retrieval.setDocumentId(
                            String.valueOf(result.documentId()));
                    retrieval.setChunkIndex(0);
                    retrieval.setChunkText(result.retrievalText());
                    retrieval.setTitle(result.title());
                    retrieval.setSource(result.source());
                    retrieval.setScore(result.score());
                    return retrieval;
                })
                .toList();
        return new JsonRecordService.DetailedSearchResult(
                response,
                RetrievalOutcome.ofResults(traceResults),
                traceResults);
    }

    @Test
    void toolMetadataIsProvided() {
        assertNotNull(tool.getToolMetadata());
        assertNotNull(tool.getToolDefinition());
    }

    @Test
    void singleArgCallDelegatesAndBlankInputFallsBackToEmptyObject()
            throws Exception {
        // 1 参委托：无服务端 ToolContext 时在上下文提取处拒绝。
        IllegalStateException noContext = assertThrows(
                IllegalStateException.class,
                () -> tool.call("{\"query\":\"sofa\"}"));
        assertTrue(noContext.getMessage().contains("Missing server-owned"));

        JsonNode payload = objectMapper.readTree("{\"k\":1}");
        JsonRecordSearchResponse response = new JsonRecordSearchResponse(
                "sofa", List.of(result(payload)));
        when(service.searchAuthorizedDetailed(
                eq("sofa"), any(), any(), any(), any()))
                .thenReturn(detailed(response));
        AuthorizedRetrievalContext context = context(24_000);

        JsonNode output = objectMapper.readTree(tool.call(
                "{\"query\":\"sofa\"}", toolContext(context)));
        assertEquals(1, output.path("resultCount").asInt());

        // 空白入参 → 回退 "{}" → query 缺失被拒。
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> tool.call("   ", toolContext(context)));
        assertTrue(error.getMessage().contains("must not be blank"));
    }

    @Test
    void oversizedOutputIsReplacedWithBudgetErrorEnvelope()
            throws Exception {
        // query 本体撑爆字符预算：记录清空后整体输出仍超限 →
        // 全量替换为错误信封（而非逐记录截断）。
        String giantQuery = "q".repeat(3_000);
        JsonRecordSearchResponse response = new JsonRecordSearchResponse(
                "sofa", List.of());
        when(service.searchAuthorizedDetailed(
                eq(giantQuery), any(), any(), any(), any()))
                .thenReturn(detailed(response));

        String json = tool.call(
                "{\"query\":\"" + giantQuery + "\"}",
                toolContext(context(1024)));

        JsonNode output = objectMapper.readTree(json);
        assertEquals(0, output.path("resultCount").asInt());
        assertTrue(output.path("records").isEmpty());
        assertTrue(output.path("truncated").asBoolean());
        assertTrue(output.path("error").asText()
                .contains("exceeded the configured character budget"));
        // 错误信封本身不再包含原始超大 query。
        assertFalse(json.contains(giantQuery));
    }
}
