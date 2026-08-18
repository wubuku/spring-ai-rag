package com.springairag.core.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordSearchResponse;
import com.springairag.api.dto.JsonRecordSearchResult;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.config.RagStructuredRecordProperties;
import com.springairag.core.service.JsonRecordService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在服务端授权范围内检索 JSON 结构化记录的只读 Spring AI Tool。
 */
@Component
public class JsonRecordSearchTool implements ToolCallback {

    public static final String NAME = "searchJsonRecords";

    private static final ToolDefinition DEFINITION = ToolDefinition.builder()
            .name(NAME)
            .description("Search authorized JSON records by natural-language evidence "
                    + "and an optional exact JSON object containment filter.")
            .inputSchema("""
                    {"type":"object","properties":{"query":{"type":"string","description":"Concise standalone natural-language query"},"payloadContains":{"type":"object","description":"Optional exact JSON subtree that each payload must contain"},"maxResults":{"type":"integer","description":"Optional result count, capped by server policy","minimum":1}},"required":["query"],"additionalProperties":false}
                    """)
            .build();

    private final ObjectMapper objectMapper;
    private final JsonRecordService jsonRecordService;
    private final RagStructuredRecordProperties properties;

    public JsonRecordSearchTool(
            ObjectMapper objectMapper,
            JsonRecordService jsonRecordService,
            com.springairag.core.config.RagProperties ragProperties) {
        this.objectMapper = objectMapper;
        this.jsonRecordService = jsonRecordService;
        this.properties = ragProperties.getStructuredRecords();
    }

    public boolean isEnabled() {
        return properties.isAgentToolEnabled();
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
        if (!isEnabled()) {
            throw new IllegalStateException("searchJsonRecords is disabled");
        }
        AuthorizedRetrievalContext context = context(toolContext);
        JsonNode arguments = parse(toolInput);
        String query = context.trace().normalizeQuery(
                text(arguments.get("query")));
        if (query.isBlank()) {
            throw new IllegalArgumentException(
                    "searchJsonRecords.query must not be blank");
        }
        JsonNode payloadContains = arguments.get("payloadContains");
        int requested = integer(
                arguments.get("maxResults"),
                properties.getAgentToolMaxResults());
        int limit = Math.min(
                Math.max(requested, 1),
                Math.min(
                        properties.getAgentToolMaxResults(),
                        context.options().maxResults()));

        RetrievalTraceCollector trace = context.trace();
        long startedAt = System.nanoTime();
        trace.recordToolStarted(null, NAME, query);
        int exposedCount = 0;
        try {
            if (!trace.tryBeginRetrieval(query)) {
                return serialize(Map.of(
                        "query", query,
                        "resultCount", 0,
                        "records", List.of(),
                        "budgetExhausted", true));
            }
            RetrievalConfig config = RetrievalConfig.builder()
                    .maxResults(limit)
                    .minScore(context.options().minScore())
                    .useHybridSearch(context.options().useHybridSearch())
                    .useRerank(context.options().useRerank())
                    .vectorWeight(context.options().vectorWeight())
                    .fulltextWeight(context.options().fulltextWeight())
                    .build();
            JsonRecordService.DetailedSearchResult detailed =
                    jsonRecordService.searchAuthorizedDetailed(
                            query,
                            context.filters(),
                            payloadContains,
                            context.scope(),
                            config);
            JsonRecordSearchResponse response = detailed.response();
            List<RetrievalResult> traceResults = detailed.traceResults();
            trace.recordOutcome(detailed.outcome());

            List<Map<String, Object>> records = new ArrayList<>();
            List<RetrievalResult> exposed = new ArrayList<>();
            for (int i = 0; i < response.results().size(); i++) {
                JsonRecordSearchResult result = response.results().get(i);
                RetrievalResult traceResult = traceResults.get(i);
                String citationId = trace.citationId(traceResult);
                if (citationId == null) {
                    continue;
                }
                records.add(record(result, citationId));
                exposed.add(traceResult);
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("query", query);
            if (payloadContains != null) {
                output.put("payloadContains", payloadContains);
            }
            output.put("resultCount", records.size());
            output.put("records", records);

            int maxCharacters = context.maxToolResultCharacters();
            while (!records.isEmpty()
                    && serialize(output).length() > maxCharacters) {
                records.removeLast();
                exposed.removeLast();
                output.put("resultCount", records.size());
                output.put("truncated", true);
            }
            String json = serialize(output);
            if (json.length() > maxCharacters) {
                exposed.clear();
                json = serialize(Map.of(
                        "resultCount", 0,
                        "records", List.of(),
                        "truncated", true,
                        "error", "tool result exceeded the configured character budget"));
            }
            trace.markExposed(exposed);
            exposedCount = exposed.size();
            return json;
        } finally {
            trace.recordToolFinished(
                    null,
                    NAME,
                    exposedCount,
                    (System.nanoTime() - startedAt) / 1_000_000L);
        }
    }

    private Map<String, Object> record(
            JsonRecordSearchResult result, String citationId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("citationId", citationId);
        item.put("documentId", result.documentId());
        item.put("collectionKey", result.collectionKey());
        item.put("externalId", result.externalId());
        item.put("title", result.title());
        item.put("retrievalText", result.retrievalText());
        item.put("score", result.score());
        JsonNode payload = result.jsonbPayload();
        if (payload != null
                && serializeBytes(payload).length
                <= properties.getAgentToolMaxPayloadBytes()) {
            item.put("jsonbPayload", payload);
            item.put("payloadOmitted", false);
        } else {
            item.put("payloadOmitted", payload != null);
        }
        return item;
    }

    private AuthorizedRetrievalContext context(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new IllegalStateException("Missing server-owned tool context");
        }
        Object value = toolContext.getContext().get(
                KnowledgeSearchTool.CONTEXT_KEY);
        if (value instanceof AuthorizedRetrievalContext context) {
            return context;
        }
        throw new IllegalStateException(
                "Missing server-owned authorized retrieval context");
    }

    private JsonNode parse(String input) {
        try {
            JsonNode parsed = objectMapper.readTree(
                    input == null || input.isBlank() ? "{}" : input);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException(
                        "searchJsonRecords arguments must be a JSON object");
            }
            Set<String> allowed = Set.of(
                    "query", "payloadContains", "maxResults");
            parsed.fieldNames().forEachRemaining(field -> {
                if (!allowed.contains(field)) {
                    throw new IllegalArgumentException(
                            "Unsupported searchJsonRecords argument: " + field);
                }
            });
            return parsed;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid searchJsonRecords arguments", e);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to serialize JSON record tool result", e);
        }
    }

    private byte[] serializeBytes(JsonNode value) {
        return serialize(value).getBytes(StandardCharsets.UTF_8);
    }

    private String text(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private int integer(JsonNode value, int fallback) {
        return value != null && value.canConvertToInt()
                ? value.intValue()
                : fallback;
    }
}
