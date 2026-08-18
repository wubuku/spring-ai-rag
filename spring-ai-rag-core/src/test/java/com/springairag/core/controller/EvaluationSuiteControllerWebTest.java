package com.springairag.core.controller;

import com.springairag.api.dto.EvaluationSuiteResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.evaluation.EvaluationSuiteService;
import com.springairag.core.exception.RagException;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EvaluationSuiteController.class)
@Import({
        GlobalExceptionHandler.class,
        ApiVersionConfig.class,
        EvaluationSuiteControllerWebTest.RagPropertiesTestConfig.class
})
class EvaluationSuiteControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EvaluationSuiteService service;

    @TestConfiguration
    static class RagPropertiesTestConfig {
        @Bean
        RagProperties ragProperties() {
            return new RagProperties();
        }
    }

    @Test
    void getSuiteByKeyReturnsOwnerScopedSuiteOrNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getSuite("furniture-quality")).thenReturn(
                new EvaluationSuiteResponse(
                        id,
                        "furniture-quality",
                        "Furniture",
                        "db:owner",
                        OffsetDateTime.parse("2026-08-18T00:00:00Z")));
        when(service.getSuite("hidden-suite")).thenThrow(
                new RagException(ErrorCode.NOT_FOUND, "Suite not found"));

        mockMvc.perform(get(
                        "/api/v1/rag/evaluation/suites/{suiteKey}",
                        "furniture-quality"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.suiteKey").value("furniture-quality"))
                .andExpect(jsonPath("$.ownerPrincipalId").value("db:owner"));

        mockMvc.perform(get(
                        "/api/v1/rag/evaluation/suites/{suiteKey}",
                        "hidden-suite"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
