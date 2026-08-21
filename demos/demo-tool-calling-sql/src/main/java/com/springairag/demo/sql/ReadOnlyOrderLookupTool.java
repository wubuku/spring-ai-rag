package com.springairag.demo.sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.service.RagChatToolContextKeys;
import com.springairag.api.service.RagChatToolRequestContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.OffsetDateTime;

/**
 * Fixed-shape, read-only SQL example.
 *
 * <p>The model supplies filters only. It never supplies SQL, table names,
 * owner identifiers, or a limit outside the server cap.</p>
 */
@Component
public final class ReadOnlyOrderLookupTool implements ToolCallback {

    public static final String NAME = "lookupOrders";
    private static final int MAX_RESULTS = 10;
    private static final int QUERY_TIMEOUT_SECONDS = 2;
    private static final ToolDefinition DEFINITION = ToolDefinition.builder()
            .name(NAME)
            .description("Find the caller's own orders by optional status or text.")
            .inputSchema("""
                    {"type":"object","properties":{"status":{"type":"string"},"query":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}
                    """)
            .build();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReadOnlyOrderLookupTool(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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
        RagChatToolRequestContext request = requestContext(toolContext);
        JsonNode input = parse(toolInput);
        String status = text(input, "status");
        String query = text(input, "query");
        int limit = Math.min(MAX_RESULTS, Math.max(
                1, input.path("limit").asInt(MAX_RESULTS)));
        String queryPattern = query.isBlank() ? null : "%" + query + "%";

        List<Map<String, Object>> rows = jdbcTemplate.query(
                connection -> {
                    var statement = connection.prepareStatement("""
                            SELECT order_id, status, total_amount, created_at
                            FROM demo_orders
                            WHERE owner_principal_id = ?
                              AND (? IS NULL OR status = ?)
                              AND (? IS NULL OR order_id ILIKE ?)
                            ORDER BY created_at DESC, order_id
                            LIMIT ?
                            """);
                    statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    statement.setString(1, request.principalId());
                    statement.setString(2, status.isBlank() ? null : status);
                    statement.setString(3, status.isBlank() ? null : status);
                    statement.setString(4, queryPattern);
                    statement.setString(5, queryPattern);
                    statement.setInt(6, limit);
                    return statement;
                },
                (resultSet, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("orderId", resultSet.getString("order_id"));
                    row.put("status", resultSet.getString("status"));
                    row.put("totalAmount", resultSet.getBigDecimal("total_amount"));
                    OffsetDateTime createdAt = resultSet.getObject(
                            "created_at", OffsetDateTime.class);
                    row.put("createdAt",
                            createdAt == null ? null : createdAt.toString());
                    return row;
                });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("owner", request.principalId());
        result.put("count", rows.size());
        result.put("orders", rows);
        return write(result);
    }

    private RagChatToolRequestContext requestContext(ToolContext context) {
        if (context == null || context.getContext() == null
                || !(context.getContext().get(RagChatToolContextKeys.REQUEST)
                instanceof RagChatToolRequestContext request)) {
            throw new IllegalStateException(
                    "Missing server-owned chat tool request context");
        }
        return request;
    }

    private JsonNode parse(String input) {
        try {
            JsonNode node = objectMapper.readTree(
                    input == null || input.isBlank() ? "{}" : input);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("Tool arguments must be an object");
            }
            node.fieldNames().forEachRemaining(name -> {
                if (!List.of("status", "query", "limit").contains(name)) {
                    throw new IllegalArgumentException(
                            "Unsupported lookupOrders argument: " + name);
                }
            });
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid lookupOrders arguments", e);
        }
    }

    private String text(JsonNode node, String field) {
        return node.path(field).isTextual()
                ? node.path(field).textValue().trim()
                : "";
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize lookupOrders result", e);
        }
    }
}
