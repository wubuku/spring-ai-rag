package com.springairag.core.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordSearchResponse;
import com.springairag.api.dto.JsonRecordSearchResult;
import com.springairag.api.dto.RetrievalConfig;
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
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * searchJsonRecords 预算与守卫分支（Batch 307）：空白查询拒绝、
 * 非对象参数拒绝、服务端上下文缺失、检索预算耗尽短路、不可引用
 * 来源跳过、结果字符预算逐条收缩、payload 省略标记、maxResults
 * 回退与钳制、payloadContains 回显。
 */
class JsonRecordSearchToolBudgetTest {

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

    // ── 参数守卫 ────────────────────────────────────────────────────

    @Test
    void blankOrNonTextualQueryRejected() {
        AuthorizedRetrievalContext context = context(newCollector(2, 2, 10));

        assertThrows(IllegalArgumentException.class,
                () -> tool.call("{}", toolContext(context)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.call("{\"query\":\"\"}", toolContext(context)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.call("{\"query\":42}", toolContext(context)));
    }

    @Test
    void nonObjectArgumentsRejected() {
        AuthorizedRetrievalContext context = context(newCollector(2, 2, 10));

        IllegalArgumentException arrayInput = assertThrows(
                IllegalArgumentException.class,
                () -> tool.call("[]", toolContext(context)));
        assertTrue(arrayInput.getMessage().contains("must be a JSON object"));

        IllegalArgumentException malformed = assertThrows(
                IllegalArgumentException.class,
                () -> tool.call("not json", toolContext(context)));
        assertTrue(malformed.getMessage()
                .contains("Invalid searchJsonRecords arguments"));
    }

    @Test
    void missingServerContextRejected() {
        // 单参入口无 ToolContext。
        assertThrows(IllegalStateException.class,
                () -> tool.call("{\"query\":\"sofa\"}"));
        // 双参入口缺少授权检索上下文键。
        assertThrows(IllegalStateException.class, () -> tool.call(
                "{\"query\":\"sofa\"}",
                new ToolContext(Map.of("unrelated", "value"))));
    }

    // ── 检索预算 ────────────────────────────────────────────────────

    @Test
    void retrievalBudgetExhaustedReturnsFlaggedEmptyResponse()
            throws Exception {
        // maxRetrievalCalls 归一化下限为 1：先消耗掉唯一预算，
        // 再触发工具调用时申请失败。
        RetrievalTraceCollector trace = newCollector(1, 2, 10);
        assertTrue(trace.tryBeginRetrieval("warmup"));
        AuthorizedRetrievalContext context = context(trace);

        JsonNode output = objectMapper.readTree(tool.call(
                "{\"query\":\"sofa\"}", toolContext(context)));

        assertEquals(0, output.path("resultCount").asInt());
        assertTrue(output.path("records").isEmpty());
        assertTrue(output.path("budgetExhausted").asBoolean());
        verify(service, never()).searchAuthorizedDetailed(
                any(), any(), any(), any(), any());
    }

    @Test
    void uncitableSourceBeyondUniqueBudgetIsSkipped() throws Exception {
        // maxUniqueSources=1：第二个结果拿不到 citationId，被跳过。
        RetrievalTraceCollector trace = newCollector(2, 2, 1);
        AuthorizedRetrievalContext context = context(trace);
        JsonRecordSearchResponse response = new JsonRecordSearchResponse(
                "sofa",
                List.of(result(11L, objectMapper.nullNode()),
                        result(12L, objectMapper.nullNode())));
        when(service.searchAuthorizedDetailed(
                eq("sofa"), any(), any(), same(context.scope()),
                any(RetrievalConfig.class)))
                .thenReturn(detailed(response));

        JsonNode output = objectMapper.readTree(tool.call(
                "{\"query\":\"sofa\"}", toolContext(context)));

        assertEquals(1, output.path("resultCount").asInt());
        assertEquals("S1", output.path("records").get(0)
                .path("citationId").asText());
        assertEquals("11", output.path("records").get(0)
                .path("documentId").asText());
    }

    // ── 结果字符预算 ────────────────────────────────────────────────

    @Test
    void truncationDropsLastResultsUntilWithinCharacterBudget()
            throws Exception {
        AuthorizedRetrievalContext context =
                new AuthorizedRetrievalContext(
                        RetrievalScope.anyAssigned(null, null),
                        new RetrievalOptions(5, 0.2, true, false, 0.5, 0.5),
                        newCollector(2, 2, 10),
                        "json-tool-truncate",
                        ChatPrincipal.local(),
                        1_024);
        JsonRecordSearchResponse response = new JsonRecordSearchResponse(
                "sofa",
                List.of(result(11L, null), result(12L, null)));
        when(service.searchAuthorizedDetailed(
                eq("sofa"), any(), any(), same(context.scope()),
                any(RetrievalConfig.class)))
                .thenReturn(detailed(response));

        JsonNode output = objectMapper.readTree(tool.call(
                "{\"query\":\"sofa\"}", toolContext(context)));

        // 大文本记录触发从尾部逐条丢弃并打上 truncated 标记。
        assertTrue(output.path("truncated").asBoolean());
        assertTrue(output.path("records").size() < 2);
        assertEquals(output.path("records").size(),
                output.path("resultCount").asInt());
    }

    // ── payload 边界标记 ────────────────────────────────────────────

    @Test
    void payloadBoundariesAreFlaggedExplicitly() throws Exception {
        properties.getStructuredRecords().setAgentToolMaxPayloadBytes(4_096);
        AuthorizedRetrievalContext context = context(newCollector(2, 2, 10));
        JsonNode smallPayload = objectMapper.readTree("{\"sku\":\"S-1\"}");
        when(service.searchAuthorizedDetailed(
                eq("sofa"), any(), any(), same(context.scope()),
                any(RetrievalConfig.class)))
                .thenReturn(detailed(new JsonRecordSearchResponse(
                        "sofa", List.of(result(11L, smallPayload)))));

        JsonNode output = objectMapper.readTree(tool.call(
                "{\"query\":\"sofa\"}", toolContext(context)));

        JsonNode record = output.path("records").get(0);
        assertEquals("S-1", record.path("jsonbPayload").path("sku").asText());
        assertFalse(record.path("payloadOmitted").asBoolean());

        // null payload：不输出 jsonbPayload，且 payloadOmitted=false。
        when(service.searchAuthorizedDetailed(
                eq("sofa"), any(), any(), same(context.scope()),
                any(RetrievalConfig.class)))
                .thenReturn(detailed(new JsonRecordSearchResponse(
                        "sofa", List.of(result(11L, null)))));
        JsonNode withoutPayload = objectMapper.readTree(tool.call(
                "{\"query\":\"sofa\"}", toolContext(context)));
        assertTrue(withoutPayload.path("records").get(0)
                .path("jsonbPayload").isMissingNode());
        assertFalse(withoutPayload.path("records").get(0)
                .path("payloadOmitted").asBoolean());
    }

    @Test
    void payloadContainsFilterEchoedInOutput() throws Exception {
        AuthorizedRetrievalContext context = context(newCollector(2, 2, 10));
        JsonNode filter = objectMapper.readTree("{\"status\":\"active\"}");
        when(service.searchAuthorizedDetailed(
                eq("sofa"), any(), eq(filter), same(context.scope()),
                any(RetrievalConfig.class)))
                .thenReturn(detailed(new JsonRecordSearchResponse(
                        "sofa", List.of())));

        JsonNode output = objectMapper.readTree(tool.call(
                "{\"query\":\"sofa\","
                        + "\"payloadContains\":{\"status\":\"active\"}}",
                toolContext(context)));

        assertEquals("active", output.path("payloadContains")
                .path("status").asText());
    }

    // ── maxResults 回退与钳制 ───────────────────────────────────────

    @Test
    void maxResultsFallsBackAndClamps() throws Exception {
        // 四次调用都需要检索预算。
        AuthorizedRetrievalContext context = context(newCollector(8, 8, 10));
        when(service.searchAuthorizedDetailed(
                any(), any(), any(), any(), any(RetrievalConfig.class)))
                .thenReturn(detailed(new JsonRecordSearchResponse(
                        "sofa", List.of())));

        // 缺省 → 属性默认；非整数节点 → 回退默认。
        tool.call("{\"query\":\"sofa\"}", toolContext(context));
        tool.call("{\"query\":\"sofa\",\"maxResults\":\"lots\"}",
                toolContext(context));
        // 0 与负数 → 收敛到 1。
        tool.call("{\"query\":\"sofa\",\"maxResults\":0}",
                toolContext(context));
        tool.call("{\"query\":\"sofa\",\"maxResults\":-3}",
                toolContext(context));

        ArgumentCaptor<RetrievalConfig> configs =
                ArgumentCaptor.forClass(RetrievalConfig.class);
        verify(service, org.mockito.Mockito.times(4))
                .searchAuthorizedDetailed(
                        any(), any(), any(), any(), configs.capture());
        assertEquals(5, configs.getAllValues().get(0).getMaxResults());
        assertEquals(5, configs.getAllValues().get(1).getMaxResults());
        assertEquals(1, configs.getAllValues().get(2).getMaxResults());
        assertEquals(1, configs.getAllValues().get(3).getMaxResults());
    }

    // ── fixture ─────────────────────────────────────────────────────

    private RetrievalTraceCollector newCollector(
            int maxRetrievalCalls, int maxToolRounds, int maxUniqueSources) {
        return new RetrievalTraceCollector(
                maxRetrievalCalls, maxToolRounds, maxUniqueSources);
    }

    private AuthorizedRetrievalContext context(RetrievalTraceCollector trace) {
        return new AuthorizedRetrievalContext(
                RetrievalScope.anyAssigned(null, null),
                new RetrievalOptions(5, 0.2, true, false, 0.5, 0.5),
                trace,
                "json-tool-budget",
                ChatPrincipal.local());
    }

    private ToolContext toolContext(AuthorizedRetrievalContext context) {
        return new ToolContext(Map.of(
                KnowledgeSearchTool.CONTEXT_KEY, context));
    }

    private JsonRecordSearchResult result(long documentId, JsonNode payload) {
        String text = payload != null ? "短文本" : "x".repeat(600);
        return new JsonRecordSearchResult(
                documentId,
                7L,
                "records:v1",
                "sofa-" + documentId,
                "破皮沙发",
                "catalog",
                text,
                payload,
                0.9,
                0.8,
                0.7,
                Map.of("tenant", "demo"));
    }

    private JsonRecordService.DetailedSearchResult detailed(
            JsonRecordSearchResponse response) {
        List<RetrievalResult> traceResults = response.results().stream()
                .map(result -> {
                    RetrievalResult retrieval = new RetrievalResult();
                    retrieval.setDocumentId(String.valueOf(result.documentId()));
                    retrieval.setChunkIndex(0);
                    retrieval.setChunkText(result.retrievalText());
                    retrieval.setTitle(result.title());
                    retrieval.setSource(result.source());
                    retrieval.setScore(result.score());
                    retrieval.setVectorScore(result.vectorScore());
                    retrieval.setFulltextScore(result.fulltextScore());
                    retrieval.setMetadata(result.metadata());
                    return retrieval;
                })
                .toList();
        return new JsonRecordService.DetailedSearchResult(
                response,
                RetrievalOutcome.ofResults(traceResults),
                traceResults);
    }
}
