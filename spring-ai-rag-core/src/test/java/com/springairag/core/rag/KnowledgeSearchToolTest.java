package com.springairag.core.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSearchToolTest {

    private ObjectMapper objectMapper;
    private HybridRetrieverService hybridRetriever;
    private KnowledgeSearchTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        hybridRetriever = mock(HybridRetrieverService.class);
        tool = new KnowledgeSearchTool(
                objectMapper,
                hybridRetriever,
                new RetrievalDocumentMapper());
    }

    @Test
    void schemaExposesOnlyQueryAndOptionalMaxResults() throws Exception {
        JsonNode schema = objectMapper.readTree(
                tool.getToolDefinition().inputSchema());

        assertTrue(schema.path("properties").has("query"));
        assertTrue(schema.path("properties").has("maxResults"));
        assertFalse(schema.path("properties").has("scope"));
        assertFalse(schema.path("properties").has("collectionIds"));
        assertEquals(
                List.of("query"),
                objectMapper.convertValue(
                        schema.path("required"),
                        objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, String.class)));
    }

    @Test
    void callUsesServerScopeCapsLimitAndCachesRepeatedQuery() throws Exception {
        RetrievalScope scope = RetrievalScope.selectedCollections(
                List.of(7L),
                List.of(11L, 12L),
                null);
        RetrievalOptions options =
                new RetrievalOptions(3, 0.25, true, true, 0.55, 0.45);
        RetrievalTraceCollector trace = new RetrievalTraceCollector(2, 3, 10);
        AuthorizedRetrievalContext authorized =
                new AuthorizedRetrievalContext(
                        scope,
                        options,
                        trace,
                        "session-tool",
                        new ChatPrincipal("db:42", "DATABASE_API_KEY", false));
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId("11");
        result.setChunkIndex(0);
        result.setTitle("品牌规范");
        result.setChunkText("风格基调应保持克制。");
        result.setScore(0.8);
        when(hybridRetriever.searchInScope(
                eq("风格基调"),
                same(scope),
                any(),
                eq(3),
                any()))
                .thenReturn(List.of(result));
        ToolContext toolContext = new ToolContext(Map.of(
                KnowledgeSearchTool.CONTEXT_KEY,
                authorized));

        String first = tool.call(
                "{\"query\":\"风格基调\",\"maxResults\":99}",
                toolContext);
        String second = tool.call(
                "{\"query\":\" 风格基调 \"}",
                toolContext);

        JsonNode output = objectMapper.readTree(first);
        assertEquals(1, output.path("resultCount").asInt());
        assertEquals("11", output.path("sources").get(0)
                .path("documentId").asText());
        assertEquals(first, second);
        ArgumentCaptor<RetrievalScope> usedScope =
                ArgumentCaptor.forClass(RetrievalScope.class);
        verify(hybridRetriever, times(1)).searchInScope(
                eq("风格基调"),
                usedScope.capture(),
                any(),
                eq(3),
                any());
        assertSame(scope, usedScope.getValue());
        assertEquals(1, trace.retrievalCalls());
    }

    @Test
    void multipleCallsKeepCitationsAlignedWithTheFinalSourceSnapshot()
            throws Exception {
        RetrievalTraceCollector trace = new RetrievalTraceCollector(3, 3, 2);
        AuthorizedRetrievalContext authorized =
                new AuthorizedRetrievalContext(
                        RetrievalScope.unscoped(),
                        new RetrievalOptions(3, 0.25, true, true, 0.55, 0.45),
                        trace,
                        "session-citations",
                        ChatPrincipal.local());
        RetrievalResult first = result("11", "First");
        RetrievalResult shared = result("12", "Shared");
        RetrievalResult overBudget = result("13", "Over budget");
        when(hybridRetriever.searchInScope(
                eq("first query"), any(), any(), eq(3), any()))
                .thenReturn(List.of(first, shared));
        when(hybridRetriever.searchInScope(
                eq("second query"), any(), any(), eq(3), any()))
                .thenReturn(List.of(shared, overBudget));
        ToolContext toolContext = new ToolContext(Map.of(
                KnowledgeSearchTool.CONTEXT_KEY,
                authorized));

        JsonNode firstOutput = objectMapper.readTree(tool.call(
                "{\"query\":\"first query\"}", toolContext));
        JsonNode secondOutput = objectMapper.readTree(tool.call(
                "{\"query\":\"second query\"}", toolContext));

        assertEquals("S1", firstOutput.path("sources").get(0)
                .path("citationId").asText());
        assertEquals("S2", firstOutput.path("sources").get(1)
                .path("citationId").asText());
        assertEquals(1, secondOutput.path("resultCount").asInt());
        assertEquals("12", secondOutput.path("sources").get(0)
                .path("documentId").asText());
        assertEquals("S2", secondOutput.path("sources").get(0)
                .path("citationId").asText());
        assertEquals(
                List.of("11", "12"),
                trace.sources().stream()
                        .map(RetrievalResult::getDocumentId)
                        .toList());
        assertEquals("S1", trace.citationId(first));
        assertEquals("S2", trace.citationId(shared));
        assertNull(trace.citationId(overBudget));
        assertEquals(
                List.of(2, 1),
                trace.drainToolEvents().stream()
                        .filter(com.springairag.core.chat.ChatEvent.ToolFinished.class::isInstance)
                        .map(com.springairag.core.chat.ChatEvent.ToolFinished.class::cast)
                        .map(com.springairag.core.chat.ChatEvent.ToolFinished::resultCount)
                        .toList());
    }

    @Test
    void callRejectsMissingServerOwnedContext() {
        assertThrows(
                IllegalStateException.class,
                () -> tool.call("{\"query\":\"anything\"}",
                        new ToolContext(Map.of())));
    }

    @Test
    void callTruncatesToolOutputWithinServerCharacterBudget() throws Exception {
        RetrievalOptions options =
                new RetrievalOptions(3, 0.25, true, true, 0.55, 0.45);
        AuthorizedRetrievalContext authorized =
                new AuthorizedRetrievalContext(
                        RetrievalScope.unscoped(),
                        options,
                        new RetrievalTraceCollector(2, 3, 10),
                        "session-budget",
                        ChatPrincipal.local(),
                        1024);
        RetrievalTraceCollector trace = authorized.trace();
        RetrievalResult result = result("11", "Large source");
        result.setChunkText("x".repeat(5000));
        RetrievalResult removed = result("12", "Removed source");
        removed.setChunkText("y".repeat(5000));
        when(hybridRetriever.searchInScope(
                eq("budget"),
                any(),
                any(),
                eq(3),
                any()))
                .thenReturn(List.of(result, removed));

        String output = tool.call(
                "{\"query\":\"budget\"}",
                new ToolContext(Map.of(
                        KnowledgeSearchTool.CONTEXT_KEY,
                        authorized)));

        assertTrue(output.length() <= 1024);
        JsonNode json = objectMapper.readTree(output);
        assertTrue(json.path("truncated").asBoolean());
        assertEquals(1, json.path("resultCount").asInt());
        assertTrue(json.path("sources").get(0).path("snippet").asText().length()
                < 5000);
        assertEquals(
                List.of("11"),
                trace.sources().stream()
                        .map(RetrievalResult::getDocumentId)
                        .toList());
        assertEquals(
                1,
                trace.drainToolEvents().stream()
                        .filter(com.springairag.core.chat.ChatEvent.ToolFinished.class::isInstance)
                        .map(com.springairag.core.chat.ChatEvent.ToolFinished.class::cast)
                        .findFirst()
                        .orElseThrow()
                        .resultCount());
    }

    @Test
    void callKeepsValidJsonWithinBudgetWhenQueryAndMetadataAreLarge()
            throws Exception {
        AuthorizedRetrievalContext authorized =
                new AuthorizedRetrievalContext(
                        RetrievalScope.unscoped(),
                        new RetrievalOptions(1, 0.25, true, true, 0.55, 0.45),
                        new RetrievalTraceCollector(2, 3, 10),
                        "session-large-query",
                        ChatPrincipal.local(),
                        1024);
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId("11");
        result.setTitle("t".repeat(2000));
        result.setChunkText("x".repeat(5000));
        result.setScore(0.8);
        String query = "\\\"".repeat(2500);
        when(hybridRetriever.searchInScope(
                eq(query),
                any(),
                any(),
                eq(1),
                any()))
                .thenReturn(List.of(result));

        String output = tool.call(
                objectMapper.writeValueAsString(Map.of("query", query)),
                new ToolContext(Map.of(
                        KnowledgeSearchTool.CONTEXT_KEY,
                        authorized)));

        assertTrue(output.length() <= 1024);
        JsonNode json = objectMapper.readTree(output);
        assertTrue(json.path("truncated").asBoolean());
        assertTrue(json.path("query").asText().length() < query.length());
    }

    private RetrievalResult result(String documentId, String title) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkIndex(0);
        result.setTitle(title);
        result.setChunkText(title + " content");
        result.setScore(0.8);
        return result;
    }
}
