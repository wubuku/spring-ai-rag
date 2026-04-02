package com.springairag.core.controller;

import com.springairag.core.metrics.CacheMetricsService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CacheMetricsController 测试
 */
@WebMvcTest(CacheMetricsController.class)
@Import(ApiVersionConfig.class)
class CacheMetricsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CacheMetricsService cacheMetricsService;

    @Test
    void getCacheStats_returnsStats() throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("hitCount", 100);
        stats.put("missCount", 20);
        stats.put("totalCount", 120);
        stats.put("hitRate", "83.3%");

        when(cacheMetricsService.getStats()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/cache/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hitCount").value(100))
                .andExpect(jsonPath("$.missCount").value(20))
                .andExpect(jsonPath("$.totalCount").value(120))
                .andExpect(jsonPath("$.hitRate").value("83.3%"));
    }

    @Test
    void getCacheStats_noData_returnsZeroStats() throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("hitCount", 0);
        stats.put("missCount", 0);
        stats.put("totalCount", 0);
        stats.put("hitRate", "N/A");

        when(cacheMetricsService.getStats()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/cache/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hitCount").value(0))
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.hitRate").value("N/A"));
    }
}
