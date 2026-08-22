package com.springairag.demo.sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.service.RagChatToolContextKeys;
import com.springairag.api.service.RagChatToolRequestContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.sql.Types;

/**
 * Fixed-shape, read-only SQL example.
 *
 * <p>The model supplies filters only. It never supplies SQL, table names,
 * owner identifiers, or a limit outside the server cap.</p>
 */
@Component
public final class ReadOnlyInventoryLookupTool implements ToolCallback {

    public static final String NAME = "lookupInventory";
    private static final int MAX_RESULTS = 20;
    private static final int QUERY_TIMEOUT_SECONDS = 2;
    private static final int MAX_RESULT_CHARACTERS = 8_000;
    private static final String SQL = """
            SELECT sku, warehouse_code, available_quantity, updated_at
            FROM demo_inventory
            WHERE owner_principal_id = :ownerPrincipalId
              AND (:sku IS NULL OR sku = :sku)
              AND (:warehouseCode IS NULL OR warehouse_code = :warehouseCode)
            ORDER BY updated_at DESC, sku, warehouse_code
            LIMIT :maxResults
            """;
    private static final ToolDefinition DEFINITION = ToolDefinition.builder()
            .name(NAME)
            .description("Find the caller's own inventory records by optional SKU or warehouse.")
            .inputSchema("""
                    {"type":"object","properties":{"sku":{"type":"string"},"warehouseCode":{"type":"string"},"maxResults":{"type":"integer","minimum":1,"maximum":20}},"additionalProperties":false}
                    """)
            .build();

    private final TimeoutAwareNamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReadOnlyInventoryLookupTool(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = new TimeoutAwareNamedParameterJdbcTemplate(
                jdbcTemplate.getJdbcOperations());
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
        String sku = text(input, "sku");
        String warehouseCode = text(input, "warehouseCode");
        int limit = Math.min(MAX_RESULTS, Math.max(
                1, input.path("maxResults").asInt(MAX_RESULTS)));
        int timeoutSeconds = timeoutSeconds(request.deadline());
        if (timeoutSeconds < 1) {
            return "{\"error\":\"tool_timeout\"}";
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("ownerPrincipalId", request.principalId())
                .addValue("sku", sku.isBlank() ? null : sku, Types.VARCHAR)
                .addValue("warehouseCode",
                        warehouseCode.isBlank() ? null : warehouseCode,
                        Types.VARCHAR)
                .addValue("maxResults", limit);
        List<Map<String, Object>> rows = jdbcTemplate.query(
                SQL,
                parameters,
                rowMapper(),
                timeoutSeconds);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", rows.size());
        result.put("inventory", rows);
        String serialized = write(result);
        return serialized.length() <= MAX_RESULT_CHARACTERS
                ? serialized
                : "{\"error\":\"tool_result_too_large\"}";
    }

    private int timeoutSeconds(Instant deadline) {
        if (deadline == null) {
            return QUERY_TIMEOUT_SECONDS;
        }
        long remainingMillis = Duration.between(
                Instant.now(), deadline).toMillis();
        if (remainingMillis <= 0) {
            return 0;
        }
        long remainingSeconds = Math.max(
                1, (remainingMillis + 999) / 1_000);
        return (int) Math.min(
                QUERY_TIMEOUT_SECONDS,
                Math.min(Integer.MAX_VALUE, remainingSeconds));
    }

    private RowMapper<Map<String, Object>> rowMapper() {
        return (resultSet, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sku", resultSet.getString("sku"));
            row.put("warehouseCode", resultSet.getString("warehouse_code"));
            row.put("availableQuantity",
                    resultSet.getInt("available_quantity"));
            OffsetDateTime updatedAt = resultSet.getObject(
                    "updated_at", OffsetDateTime.class);
            row.put("updatedAt",
                    updatedAt == null ? null : updatedAt.toString());
            return row;
        };
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
                if (!List.of("sku", "warehouseCode", "maxResults")
                        .contains(name)) {
                    throw new IllegalArgumentException(
                            "Unsupported lookupInventory argument: " + name);
                }
            });
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid lookupInventory arguments", e);
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
            throw new IllegalStateException(
                    "Failed to serialize lookupInventory result", e);
        }
    }

    /**
     * Uses NamedParameterJdbcTemplate's named-parameter binding while applying
     * a per-statement timeout without mutating shared JdbcTemplate settings.
     */
    private static final class TimeoutAwareNamedParameterJdbcTemplate
            extends NamedParameterJdbcTemplate {

        private TimeoutAwareNamedParameterJdbcTemplate(
                org.springframework.jdbc.core.JdbcOperations operations) {
            super(operations);
        }

        private <T> List<T> query(
                String sql,
                MapSqlParameterSource parameters,
                RowMapper<T> rowMapper,
                int timeoutSeconds) {
            PreparedStatementCreator creator =
                    getPreparedStatementCreator(sql, parameters);
            return getJdbcOperations().query(connection -> {
                java.sql.PreparedStatement statement =
                        creator.createPreparedStatement(connection);
                statement.setQueryTimeout(timeoutSeconds);
                return statement;
            }, rowMapper);
        }
    }
}
