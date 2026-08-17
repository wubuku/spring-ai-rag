package com.springairag.core.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.retrieval.HybridRetrieverService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only Spring AI tool for agentic knowledge retrieval.
 *
 * <p>Authorization is deliberately absent from the model-visible schema. The
 * server-owned {@link AuthorizedRetrievalContext} is supplied through
 * {@link ToolContext} and cannot be expanded by tool arguments.</p>
 */
@Component
public class KnowledgeSearchTool implements ToolCallback {

    public static final String NAME = "searchKnowledge";
    public static final String CONTEXT_KEY = "rag.authorized.retrieval";

    private static final ToolDefinition DEFINITION = ToolDefinition.builder()
            .name(NAME)
            .description("Search the authorized knowledge base for evidence relevant to the user's request. "
                    + "Use a concise standalone query. Only documents visible to the caller are searched.")
            .inputSchema("""
                    {"type":"object","properties":{"query":{"type":"string","description":"Concise standalone search query"},"maxResults":{"type":"integer","description":"Optional result count, capped by server policy","minimum":1}},"required":["query"]}
                    """)
            .build();

    private final ObjectMapper objectMapper;
    private final HybridRetrieverService hybridRetriever;
    private final RetrievalDocumentMapper mapper;

    public KnowledgeSearchTool(ObjectMapper objectMapper,
                               HybridRetrieverService hybridRetriever,
                               RetrievalDocumentMapper mapper) {
        this.objectMapper = objectMapper;
        this.hybridRetriever = hybridRetriever;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DEFINITION;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().returnDirect(false).build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        AuthorizedRetrievalContext context = context(toolContext);
        Map<String, Object> arguments = parse(toolInput);
        String query = context.trace().normalizeQuery(stringValue(arguments.get("query")));
        if (query.isBlank()) {
            throw new IllegalArgumentException("searchKnowledge.query must not be blank");
        }

        RetrievalTraceCollector trace = context.trace();
        long startedAt = System.nanoTime();
        trace.recordToolStarted(null, NAME, query);
        List<RetrievalResult> results = List.of();
        int exposedResultCount = 0;
        boolean budgetExhausted = false;
        String error = null;
        try {
            results = trace.cachedResults(query);
            if (results == null) {
                if (!trace.tryBeginRetrieval(query)) {
                    budgetExhausted = true;
                    error = "retrieval budget exhausted";
                } else {
                    int requested = integerValue(
                            arguments.get("maxResults"),
                            context.options().maxResults());
                    int limit = Math.min(
                            Math.max(requested, 1),
                            context.options().maxResults());
                    results = context.scope().matchNone()
                            ? List.of()
                            : hybridRetriever.searchInScope(
                                    query,
                                    context.scope(),
                                    null,
                                    limit,
                                    context.options().toConfig());
                    trace.record(query, results);
                }
            }
            List<RetrievalResult> citableResults = results.stream()
                    .filter(result -> trace.citationId(result) != null)
                    .toList();
            ToolOutput output = resultJson(
                    query,
                    citableResults,
                    budgetExhausted,
                    error,
                    context.maxToolResultCharacters(),
                    trace);
            trace.markExposed(output.exposedResults());
            exposedResultCount = output.exposedResults().size();
            return output.json();
        } finally {
            trace.recordToolFinished(
                    null,
                    NAME,
                    exposedResultCount,
                    (System.nanoTime() - startedAt) / 1_000_000L);
        }
    }

    private AuthorizedRetrievalContext context(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new IllegalStateException("Missing server-owned tool context");
        }
        Object value = toolContext.getContext().get(CONTEXT_KEY);
        if (value instanceof AuthorizedRetrievalContext context) {
            return context;
        }
        throw new IllegalStateException("Missing server-owned authorized retrieval context");
    }

    private Map<String, Object> parse(String input) {
        try {
            return objectMapper.readValue(
                    input == null || input.isBlank() ? "{}" : input,
                    new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid searchKnowledge arguments", e);
        }
    }

    private ToolOutput resultJson(
            String query,
            List<RetrievalResult> results,
            boolean budgetExhausted,
            String error,
            int maxCharacters,
            RetrievalTraceCollector trace) {
        List<Map<String, Object>> sources = new java.util.ArrayList<>();
        List<RetrievalResult> includedResults = new java.util.ArrayList<>();
        for (RetrievalResult result : results) {
            String citationId = trace.citationId(result);
            ChatSource source = mapper.toChatSource(result, citationId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("citationId", source.getCitationId());
            item.put("documentId", source.getDocumentId());
            item.put("title", source.getTitle());
            item.put("snippet", source.getChunkText());
            item.put("score", source.getScore());
            sources.add(item);
            includedResults.add(result);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("query", query);
        output.put("resultCount", sources.size());
        output.put("sources", sources);
        if (budgetExhausted) {
            output.put("budgetExhausted", true);
        }
        if (error != null) {
            output.put("error", error);
        }
        String serialized = serialize(output);
        if (serialized.length() <= maxCharacters) {
            return new ToolOutput(serialized, includedResults);
        }

        output.put("truncated", true);
        while (sources.size() > 1
                && serialize(output).length() > maxCharacters) {
            sources.removeLast();
            includedResults.removeLast();
            output.put("resultCount", sources.size());
        }
        if (!sources.isEmpty()) {
            Map<String, Object> source = sources.getFirst();
            String snippet = stringValue(source.get("snippet"));
            while (!snippet.isEmpty()
                    && serialize(output).length() > maxCharacters) {
                int overflow = serialize(output).length() - maxCharacters;
                int nextLength = Math.max(
                        0,
                        snippet.length() - Math.max(overflow, 256));
                snippet = snippet.substring(0, nextLength);
                source.put("snippet", snippet);
            }
        }
        serialized = serialize(output);
        if (serialized.length() <= maxCharacters) {
            return new ToolOutput(serialized, includedResults);
        }

        sources.clear();
        includedResults.clear();
        output.put("resultCount", 0);
        output.put("error", "tool result exceeded the configured character budget");
        serialized = serialize(output);
        if (serialized.length() <= maxCharacters) {
            return new ToolOutput(serialized, includedResults);
        }

        String originalQuery = stringValue(output.get("query"));
        output.put("query", "");
        serialized = serialize(output);
        int availableQueryCharacters =
                Math.max(0, maxCharacters - serialized.length());
        output.put(
                "query",
                originalQuery.substring(
                        0,
                        Math.min(originalQuery.length(), availableQueryCharacters)));
        serialized = serialize(output);
        while (serialized.length() > maxCharacters
                && !stringValue(output.get("query")).isEmpty()) {
            String currentQuery = stringValue(output.get("query"));
            int overflow = serialized.length() - maxCharacters;
            output.put(
                    "query",
                    currentQuery.substring(
                            0,
                            Math.max(0, currentQuery.length() - overflow)));
            serialized = serialize(output);
        }
        return new ToolOutput(serialized, includedResults);
    }

    private String serialize(Map<String, Object> output) {
        try {
            return objectMapper.writeValueAsString(output);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize knowledge tool result", e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int integerValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private record ToolOutput(
            String json,
            List<RetrievalResult> exposedResults) {
        private ToolOutput {
            exposedResults = List.copyOf(exposedResults);
        }
    }
}
