package com.springairag.core.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.resource.StaticKnowledgeCatalog;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent-only server-owned search tool for deployment-provided static knowledge.
 */
@Component
public final class StaticKnowledgeSearchTool implements ToolCallback {

    public static final String NAME = "searchStaticKnowledge";
    private static final ToolDefinition DEFINITION = ToolDefinition.builder()
            .name(NAME)
            .description("Search deployment-provided static knowledge without using embeddings. "
                    + "Results are bounded and marked as STATIC_KNOWLEDGE. Cite returned "
                    + "sources with their sourceHandle (preferred) or citationId.")
            .inputSchema("""
                    {"type":"object","properties":{"query":{"type":"string","description":"Concise search query"},"maxResults":{"type":"integer","minimum":1}},"required":["query"],"additionalProperties":false}
                    """)
            .build();

    private final ObjectMapper objectMapper;
    private final StaticKnowledgeCatalog catalog;
    private final RetrievalDocumentMapper mapper;

    public StaticKnowledgeSearchTool(
            ObjectMapper objectMapper,
            StaticKnowledgeCatalog catalog,
            RetrievalDocumentMapper mapper) {
        this.objectMapper = objectMapper;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    public boolean isEnabled() {
        return catalog.snapshot().healthy()
                && !catalog.snapshot().chunks().isEmpty();
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
        throw new IllegalStateException(
                "Missing server-owned static knowledge context");
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        AuthorizedRetrievalContext context = context(toolContext);
        Map<String, Object> arguments = parse(toolInput);
        String query = context.trace().normalizeQuery(
                String.valueOf(arguments.getOrDefault("query", "")));
        if (query.isBlank()) {
            throw new IllegalArgumentException(
                    "searchStaticKnowledge.query must not be blank");
        }
        int requested = arguments.get("maxResults") instanceof Number number
                ? number.intValue()
                : context.options().maxResults();
        int limit = Math.min(
                Math.max(1, requested),
                context.options().maxResults());
        RetrievalTraceCollector trace = context.trace();
        if (!trace.tryBeginRetrieval(query)) {
            return json(Map.of(
                    "query", query,
                    "resultCount", 0,
                    "sources", List.of(),
                    "budgetExhausted", true));
        }
        List<Document> documents = catalog.search(
                query, limit, context.maxToolResultCharacters());
        List<RetrievalResult> results = documents.stream()
                .map(this::toResult)
                .toList();
        trace.record(results);
        List<Map<String, Object>> sources = new ArrayList<>();
        List<RetrievalResult> exposed = new ArrayList<>();
        for (RetrievalResult result : results) {
            String citationId = trace.citationId(result);
            if (citationId == null) {
                continue;
            }
            ChatSource source = mapper.toChatSource(
                    result, citationId, trace.sourceHandle(result));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("citationId", citationId);
            item.put("sourceHandle", source.getSourceHandle());
            item.put("sourceType", "STATIC_KNOWLEDGE");
            item.put("documentId", source.getDocumentId());
            item.put("title", source.getTitle());
            item.put("snippet", source.getChunkText());
            item.put("score", source.getScore());
            item.put("metadata", source.getMetadata());
            sources.add(item);
            exposed.add(result);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("query", query);
        output.put("resultCount", sources.size());
        output.put("sources", sources);
        int maxCharacters = context.maxToolResultCharacters();
        if (json(output).length() > maxCharacters) {
            output.put("truncated", true);
            while (sources.size() > 1 && json(output).length() > maxCharacters) {
                sources.remove(sources.size() - 1);
                exposed.remove(exposed.size() - 1);
                output.put("resultCount", sources.size());
            }
            if (!sources.isEmpty()) {
                Map<String, Object> first = sources.getFirst();
                String snippet = String.valueOf(first.getOrDefault("snippet", ""));
                while (!snippet.isEmpty() && json(output).length() > maxCharacters) {
                    int overflow = json(output).length() - maxCharacters;
                    snippet = snippet.substring(
                            0, Math.max(0, snippet.length() - Math.max(overflow, 128)));
                    first.put("snippet", snippet);
                }
            }
        }
        String serialized = json(output);
        if (serialized.length() > maxCharacters) {
            sources.clear();
            exposed.clear();
            output.clear();
            output.put("resultCount", 0);
            output.put("sources", List.of());
            output.put("truncated", true);
            output.put("error", "tool result exceeded the configured character budget");
            serialized = json(output);
        }
        trace.markExposed(exposed);
        return serialized;
    }

    private RetrievalResult toResult(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(String.valueOf(
                metadata.getOrDefault("documentId", document.getId())));
        Integer chunkIndex = nullableNumber(metadata.get("chunkIndex"));
        if (chunkIndex != null) {
            result.setChunkIndex(chunkIndex);
        }
        result.setTitle(String.valueOf(
                metadata.getOrDefault("title", result.getDocumentId())));
        result.setChunkText(document.getText());
        result.setScore(document.getScore() == null ? 0 : document.getScore());
        result.setMetadata(metadata);
        return result;
    }

    private AuthorizedRetrievalContext context(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new IllegalStateException("Missing server-owned tool context");
        }
        Object value = toolContext.getContext().get(
                ProjectDocumentRetriever.CONTEXT_KEY);
        if (value instanceof AuthorizedRetrievalContext context) {
            return context;
        }
        throw new IllegalStateException(
                "Missing server-owned authorized retrieval context");
    }

    private Map<String, Object> parse(String value) {
        try {
            return objectMapper.readValue(
                    value == null || value.isBlank() ? "{}" : value,
                    new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid searchStaticKnowledge arguments", e);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to serialize static knowledge tool result", e);
        }
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Integer nullableNumber(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
