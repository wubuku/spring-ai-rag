package com.springairag.core.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagHealthController 单元测试
 */
class RagHealthControllerTest {

    private JdbcTemplate jdbcTemplate;
    private RagHealthController controller;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        controller = new RagHealthController(jdbcTemplate);
    }

    @Test
    void health_databaseUp() {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM rag_documents"), eq(Integer.class)))
                .thenReturn(10);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM rag_embeddings"), eq(Integer.class)))
                .thenReturn(50);

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("UP", response.getBody().get("database"));
        assertEquals(10, response.getBody().get("documents"));
        assertEquals(50, response.getBody().get("embeddings"));
        assertNotNull(response.getBody().get("timestamp"));
    }

    @Test
    void health_databaseDown() {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("DOWN", response.getBody().get("database"));
        assertNotNull(response.getBody().get("databaseError"));
    }
}
