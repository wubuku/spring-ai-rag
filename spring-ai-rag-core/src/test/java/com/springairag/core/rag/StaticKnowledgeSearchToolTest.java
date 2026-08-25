package com.springairag.core.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import com.springairag.core.resource.StaticKnowledgeCatalog;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticKnowledgeSearchToolTest {

    @Test
    void returnsBoundedStaticSourcesAndRecordsTraceCitations() throws Exception {
        RagChatProperties properties = new RagChatProperties();
        RagChatProperties.StaticKnowledgeProperties config =
                properties.getStaticKnowledge();
        config.setEnabled(true);
        config.setLocations(List.of("classpath:static-fixture/"));
        config.setRetrievalMaxResults(1);
        config.setRetrievalMaxResultCharacters(120);

        StaticKnowledgeCatalog catalog = new StaticKnowledgeCatalog(
                new ResourceCatalog(), properties);
        ReflectionTestUtils.invokeMethod(catalog, "initialize");
        StaticKnowledgeSearchTool tool = new StaticKnowledgeSearchTool(
                new ObjectMapper(), catalog, new RetrievalDocumentMapper());

        RetrievalTraceCollector trace = new RetrievalTraceCollector(2, 2, 5);
        AuthorizedRetrievalContext authorized =
                new AuthorizedRetrievalContext(
                        RetrievalScope.unscoped(),
                        new RetrievalOptions(5, 0, true, false, 0.5, 0.5),
                        trace,
                        "static-tool",
                        ChatPrincipal.local(),
                        1_000);

        String output = tool.call(
                "{\"query\":\"X-200 电池保修期\",\"maxResults\":99}",
                new ToolContext(Map.of(
                        ProjectDocumentRetriever.CONTEXT_KEY,
                        authorized)));
        JsonNode json = new ObjectMapper().readTree(output);

        assertEquals(1, json.path("resultCount").asInt());
        assertEquals("STATIC_KNOWLEDGE",
                json.path("sources").get(0).path("sourceType").asText());
        assertTrue(json.path("sources").get(0).path("citationId")
                .asText().startsWith("S"));
        assertEquals(1, trace.sources().size());
        assertEquals("STATIC_KNOWLEDGE",
                trace.sources().getFirst().getMetadata().get("sourceType"));
    }
}
