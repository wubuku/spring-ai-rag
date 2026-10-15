package com.springairag.core.controller;

import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagAuditLog;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.CollectionPurgeAuthorization;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZonedDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DataGovernanceController.class)
@Import({GlobalExceptionHandler.class, ApiVersionConfig.class,
        DataGovernanceControllerWebTest.TestConfig.class})
class DataGovernanceControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private CollectionPurgeAuthorization collectionPurgeAuthorization;

    @TestConfiguration
    static class TestConfig {
        @Bean
        RagProperties ragProperties() {
            return new RagProperties();
        }
    }

    @Test
    void policyRouteReturnsDefaultPolicy() throws Exception {
        when(collectionPurgeAuthorization.isAllowed(any())).thenReturn(false);

        mockMvc.perform(get("/api/v1/rag/data-governance/policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyVersion").value("1"))
                .andExpect(jsonPath("$.redactionVersion")
                        .value("sensitive-data-masking-v1"))
                .andExpect(jsonPath("$.cleanup.enabled").value(true))
                .andExpect(jsonPath("$.capabilities.auditRead").value(false))
                .andExpect(jsonPath("$.retention[0].category").value("AUDIT_LOG"));
    }

    @Test
    void auditRouteRequiresManagementPrincipal() throws Exception {
        mockMvc.perform(get("/api/v1/rag/audit-logs"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.failureClass").value("AUTHORIZATION"));
    }

    @Test
    void auditRouteOmitsPersistedDetails() throws Exception {
        RagAuditLog audit = new RagAuditLog();
        audit.setId(1L);
        audit.setOperation("CREATE");
        audit.setEntityType("Alert");
        audit.setEntityId("alert-1");
        audit.setDescription("created");
        audit.setDetails("{\"prompt\":\"private\"}");
        audit.setCreatedAt(ZonedDateTime.parse("2026-10-15T00:00:00Z"));
        when(auditLogService.search(
                isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(audit), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/api/v1/rag/audit-logs")
                        .requestAttr(ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].operation").value("CREATE"))
                .andExpect(jsonPath("$.items[0].details").doesNotExist())
                .andExpect(jsonPath("$.items[0].description").value("created"));
    }
}
