package com.springairag.demo.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolContextKeys;
import com.springairag.api.service.RagChatToolRequestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadOnlyInventoryLookupToolTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private ReadOnlyInventoryLookupTool tool;
    private ToolContext context;

    @BeforeAll
    static void startDatabase() {
        String externalJdbcUrl = System.getProperty("demo.sql.it.jdbc-url");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            PGSimpleDataSource external = new PGSimpleDataSource();
            external.setUrl(externalJdbcUrl);
            external.setUser(System.getProperty(
                    "demo.sql.it.username", "postgres"));
            external.setPassword(System.getProperty(
                    "demo.sql.it.password", "postgres"));
            dataSource = external;
        } else {
            String image = System.getProperty(
                    "testcontainers.pg.image",
                    System.getenv().getOrDefault(
                            "TESTCONTAINERS_PG_IMAGE",
                            "pgvector/pgvector:pg16"));
            postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse(image)
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("demo_tool_calling_sql")
                    .withUsername("postgres")
                    .withPassword("postgres");
            postgres.start();
            dataSource = postgresDataSource(postgres);
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS demo_inventory");
        new ResourceDatabasePopulator(
                new ClassPathResource("schema.sql"))
                .execute(dataSource);
        tool = new ReadOnlyInventoryLookupTool(
                new NamedParameterJdbcTemplate(dataSource),
                new ObjectMapper());
        context = new ToolContext(Map.of(
                RagChatToolContextKeys.REQUEST,
                new RagChatToolRequestContext(
                        "principal-a", "USER", false, "session",
                        null, ChatMode.AGENT, "test/model", null)));
    }

    @Test
    void fixedQueryEnforcesPrincipalOwnershipAndFilters() throws Exception {
        String result = tool.call(
                "{\"sku\":\"SKU-1\",\"warehouseCode\":\"WH-1\","
                        + "\"maxResults\":20}", context);

        assertEquals(
                new ObjectMapper().readTree(
                        "{\"count\":1,\"inventory\":[{\"sku\":\"SKU-1\","
                                + "\"warehouseCode\":\"WH-1\","
                                + "\"availableQuantity\":12,"
                                + "\"updatedAt\":\"2026-08-21T10:00Z\"}]}"),
                new ObjectMapper().readTree(result));
    }

    @Test
    void boundInputCannotEscapeOwnerOrPredicate() throws Exception {
        String result = tool.call(
                "{\"sku\":\"SKU-1' OR '1'='1\"}", context);

        assertEquals(0, new ObjectMapper().readTree(result)
                .path("count").asInt());
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_inventory", Integer.class));
    }

    @Test
    void serverCapsLimitAtTwentyRows() throws Exception {
        for (int index = 0; index < 25; index++) {
            jdbcTemplate.update(
                    "INSERT INTO demo_inventory "
                            + "(owner_principal_id, sku, warehouse_code, "
                            + "available_quantity, updated_at) "
                            + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    "principal-a", "BULK-" + index, "WH-1", index);
        }

        String result = tool.call("{\"maxResults\":999}", context);

        assertEquals(20, new ObjectMapper().readTree(result)
                .path("count").asInt());
    }

    @Test
    void arbitrarySqlArgumentsAreRejectedAndQueryIsReadOnly() {
        int before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_inventory", Integer.class);

        assertThrows(IllegalArgumentException.class,
                () -> tool.call(
                        "{\"sql\":\"DELETE FROM demo_inventory\"}",
                        context));

        assertEquals(before, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_inventory", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_inventory "
                        + "WHERE owner_principal_id = 'principal-b'",
                Integer.class));
    }

    private static DataSource postgresDataSource(
            PostgreSQLContainer<?> container) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(container.getJdbcUrl());
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        return dataSource;
    }
}
