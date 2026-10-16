package com.springairag.core.controller;

import com.springairag.api.enums.ErrorCode;
import com.springairag.api.dto.DataErasurePreviewRequest;
import com.springairag.api.dto.DataErasurePreviewResponse;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagAuditLog;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.CollectionPurgeAuthorization;
import com.springairag.core.service.DataErasureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataGovernanceControllerTest {

    @Mock
    private AuditLogService auditLogService;
    @Mock
    private CollectionPurgeAuthorization collectionPurgeAuthorization;
    @Mock
    private DataErasureService dataErasureService;

    private DataGovernanceController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new DataGovernanceController(
                new RagProperties(), auditLogService, collectionPurgeAuthorization,
                dataErasureService);
        request = new MockHttpServletRequest();
    }

    @Test
    void erasurePreviewDelegatesWithoutChangingExistingPolicyContract() {
        DataErasurePreviewRequest requestBody =
                new DataErasurePreviewRequest(null, "session-1", null);
        UUID operationId = UUID.randomUUID();
        DataErasurePreviewResponse response = new DataErasurePreviewResponse(
                operationId, "PREVIEWED", Map.of("CHAT_HISTORY", 2L),
                "confirmation", "fingerprint", null, null);
        when(dataErasureService.preview(requestBody, request)).thenReturn(response);

        assertSame(response, controller.previewErasure(requestBody, request));
        verify(dataErasureService).preview(requestBody, request);
    }

    @Test
    void policyExposesDefaultGovernanceWithoutSecrets() {
        when(collectionPurgeAuthorization.isAllowed(request)).thenReturn(false);

        var response = controller.policy(request);

        assertEquals("1", response.policyVersion());
        assertEquals("sensitive-data-masking-v1", response.redactionVersion());
        assertTrue(response.enabled());
        assertTrue(response.cleanup().enabled());
        assertFalse(response.capabilities().collectionPurge());
        assertTrue(response.retention().stream()
                .anyMatch(item -> "AUDIT_LOG".equals(item.category())
                        && item.retentionDays() == 400));
    }

    @Test
    void auditLogsRequiresRootOrAdmin() {
        RagException error = assertThrows(RagException.class,
                () -> controller.auditLogs(0, 50, null, null, null, null, null, request));

        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCodeEnum());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void auditLogsReturnsContentFreePageForRoot() {
        request.setAttribute(ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE, true);
        RagAuditLog audit = new RagAuditLog();
        audit.setId(7L);
        audit.setOperation("CREATE");
        audit.setEntityType("Alert");
        audit.setEntityId("alert-1");
        audit.setDescription("Alert created");
        audit.setDetails("{\"secret\":\"must-not-escape\"}");
        audit.setCreatedAt(ZonedDateTime.now());
        when(auditLogService.search(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(audit), PageRequest.of(0, 50), 1));

        var response = controller.auditLogs(0, 50, null, null, null, null, null, request);

        assertEquals(1, response.totalElements());
        assertEquals(1, response.items().size());
        assertEquals("CREATE", response.items().getFirst().operation());
        assertFalse(response.items().getFirst().toString().contains("must-not-escape"));
        verify(auditLogService).search(isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    void auditLogsRejectsRangeLongerThan31Days() {
        request.setAttribute(ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE, true);

        RagException error = assertThrows(RagException.class,
                () -> controller.auditLogs(0, 50, null, null, null,
                        "2026-01-01T00:00:00Z", "2026-02-02T00:00:00Z", request));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCodeEnum());
        verifyNoInteractions(auditLogService);
    }
}
