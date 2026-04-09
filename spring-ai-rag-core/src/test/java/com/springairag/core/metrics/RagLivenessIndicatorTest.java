package com.springairag.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagLivenessIndicator Tests")
class RagLivenessIndicatorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private RagLivenessIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new RagLivenessIndicator(jdbcTemplate);
    }

    @Test
    @DisplayName("Returns UP when database is reachable")
    void health_databaseReachable_returnsUp() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        Health health = indicator.health();

        assertEquals("UP", health.getStatus().getCode());
        assertEquals("reachable", health.getDetails().get("database"));
        assertNotNull(health.getDetails().get("latencyMs"));
        verify(jdbcTemplate).queryForObject("SELECT 1", Integer.class);
    }

    @Test
    @DisplayName("Returns DOWN when database is not reachable")
    void health_databaseUnreachable_returnsDown() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new RuntimeException("Connection refused"));

        Health health = indicator.health();

        assertEquals("DOWN", health.getStatus().getCode());
        assertEquals("unreachable", health.getDetails().get("database"));
        assertNotNull(health.getDetails().get("error"));
    }

    @Test
    @DisplayName("includes latency information")
    void health_includesLatencyDetail() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        Health health = indicator.health();

        assertTrue(health.getDetails().containsKey("latencyMs"));
        assertInstanceOf(Number.class, health.getDetails().get("latencyMs"));
    }
}
