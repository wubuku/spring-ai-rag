package com.springairag.demo.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolContextKeys;
import com.springairag.api.service.RagChatToolRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadOnlyOrderLookupToolTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private ReadOnlyOrderLookupTool tool;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("demo-orders-" + UUID.randomUUID())
                .addScript("schema.sql")
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        tool = new ReadOnlyOrderLookupTool(jdbcTemplate, new ObjectMapper());
        context = new ToolContext(Map.of(
                RagChatToolContextKeys.REQUEST,
                new RagChatToolRequestContext(
                        "principal-a", "USER", false, "session",
                        null, ChatMode.AGENT, "test/model", null)));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void fixedQueryEnforcesPrincipalOwnershipAndFilters() throws Exception {
        String result = tool.call(
                "{\"status\":\"PAID\",\"limit\":5}", context);

        assertEquals(
                new ObjectMapper().readTree(
                        "{\"owner\":\"principal-a\",\"count\":1,"
                                + "\"orders\":[{\"orderId\":\"order-a\","
                                + "\"status\":\"PAID\",\"totalAmount\":12.50,"
                                + "\"createdAt\":\"2026-08-21T10:00Z\"}]}"),
                new ObjectMapper().readTree(result));
    }

    @Test
    void arbitrarySqlArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call("{\"sql\":\"DELETE FROM demo_orders\"}", context));
    }
}
