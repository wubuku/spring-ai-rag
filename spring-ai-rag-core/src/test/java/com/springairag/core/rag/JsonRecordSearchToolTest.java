package com.springairag.core.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordSearchResponse;
import com.springairag.api.dto.JsonRecordSearchResult;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.config.RagProperties;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JsonRecordSearchToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JsonRecordService service;
    private RagProperties properties;
    private JsonRecordSearchTool tool;

    @BeforeEach
    void setUp() {
        service = mock(JsonRecordService.class);
        properties = new RagProperties();
        properties.getStructuredRecords().setAgentToolEnabled(true);
        tool = new JsonRecordSearchTool(
                objectMapper, service, properties);
    }

    @Test
    void schemaDoesNotExposeAuthorizationOrSqlControls()
            throws Exception {
        JsonNode schema = objectMapper.readTree(
                tool.getToolDefinition().inputSchema());

        assertTrue(schema.path("properties").has("query"));
        assertTrue(schema.path("properties").has("payloadContains"));
        assertFalse(schema.path("properties").has("collectionIds"));
        assertFalse(schema.path("properties").has("collectionKeys"));
        assertFalse(schema.path("properties").has("scopeMode"));
        assertFalse(schema.path("properties").has("sql"));
        assertFalse(schema.path("properties").has("jsonPath"));
    }

    @Test
    void callUsesServerScopeCapsResultsAndReturnsCitablePayload()
            throws Exception {
        RetrievalScope scope = RetrievalScope.selectedCollections(
                List.of(7L), null, null);
        RetrievalTraceCollector trace =
                new RetrievalTraceCollector(3, 3, 10);
        AuthorizedRetrievalContext context =
                new AuthorizedRetrievalContext(
                        scope,
                        new RetrievalOptions(
                                9, 0.2, true, true, 0.55, 0.45),
                        trace,
                        "json-tool-session",
                        new ChatPrincipal(
                                "db:7", "DATABASE_API_KEY", false));
        JsonNode payload = objectMapper.readTree(
                "{\"status\":\"active\",\"sku\":\"S-1\"}");
        JsonNode filter = objectMapper.readTree(
                "{\"status\":\"active\"}");
        when(service.searchAuthorized(
                eq("破皮沙发"), eq(filter), same(scope),
                any(RetrievalConfig.class)))
                .thenReturn(new JsonRecordSearchResponse(
                        "破皮沙发",
                        List.of(result(payload))));

        JsonNode output = objectMapper.readTree(tool.call(
                "{\"query\":\"破皮沙发\","
                        + "\"payloadContains\":{\"status\":\"active\"},"
                        + "\"maxResults\":99}",
                toolContext(context)));

        assertEquals(1, output.path("resultCount").asInt());
        assertEquals("S1", output.path("records").get(0)
                .path("citationId").asText());
        assertEquals("active", output.path("records").get(0)
                .path("jsonbPayload").path("status").asText());
        assertEquals(1, trace.sources().size());

        ArgumentCaptor<RetrievalConfig> configCaptor =
                ArgumentCaptor.forClass(RetrievalConfig.class);
        verify(service).searchAuthorized(
                eq("破皮沙发"), eq(filter), same(scope),
                configCaptor.capture());
        assertEquals(5, configCaptor.getValue().getMaxResults());
    }

    @Test
    void oversizedPayloadIsOmittedAtRecordBoundary()
            throws Exception {
        properties.getStructuredRecords()
                .setAgentToolMaxPayloadBytes(16);
        RetrievalScope scope = RetrievalScope.anyAssigned(
                null, null);
        AuthorizedRetrievalContext context =
                new AuthorizedRetrievalContext(
                        scope,
                        new RetrievalOptions(
                                5, 0.2, true, false, 0.5, 0.5),
                        new RetrievalTraceCollector(),
                        "json-tool-budget",
                        ChatPrincipal.local());
        JsonNode payload = objectMapper.readTree(
                "{\"description\":\""
                        + "x".repeat(100) + "\"}");
        when(service.searchAuthorized(
                eq("sofa"), eq(null), same(scope),
                any(RetrievalConfig.class)))
                .thenReturn(new JsonRecordSearchResponse(
                        "sofa",
                        List.of(result(payload))));

        JsonNode output = objectMapper.readTree(tool.call(
                "{\"query\":\"sofa\"}",
                toolContext(context)));

        JsonNode record = output.path("records").get(0);
        assertTrue(record.path("payloadOmitted").asBoolean());
        assertFalse(record.has("jsonbPayload"));
    }

    @Test
    void rejectsModelAttemptsToSupplyScopeOrSql() {
        AuthorizedRetrievalContext context =
                new AuthorizedRetrievalContext(
                        RetrievalScope.noMatches(),
                        new RetrievalOptions(
                                5, 0.2, true, false, 0.5, 0.5),
                        new RetrievalTraceCollector(),
                        "json-tool-reject",
                        ChatPrincipal.local());

        assertThrows(IllegalArgumentException.class, () ->
                tool.call(
                        "{\"query\":\"sofa\",\"collectionIds\":[1]}",
                        toolContext(context)));
        assertThrows(IllegalArgumentException.class, () ->
                tool.call(
                        "{\"query\":\"sofa\",\"sql\":\"SELECT 1\"}",
                        toolContext(context)));
    }

    @Test
    void disabledToolCannotBeCalledDirectly() {
        properties.getStructuredRecords().setAgentToolEnabled(false);

        assertThrows(IllegalStateException.class, () ->
                tool.call("{\"query\":\"sofa\"}"));
    }

    private ToolContext toolContext(
            AuthorizedRetrievalContext context) {
        return new ToolContext(Map.of(
                KnowledgeSearchTool.CONTEXT_KEY,
                context));
    }

    private JsonRecordSearchResult result(JsonNode payload) {
        return new JsonRecordSearchResult(
                11L,
                7L,
                "records:v1",
                "sofa-11",
                "破皮沙发",
                "catalog",
                "一张需要修复的破皮沙发。",
                payload,
                0.9,
                0.8,
                0.7,
                Map.of("tenant", "demo"));
    }
}
