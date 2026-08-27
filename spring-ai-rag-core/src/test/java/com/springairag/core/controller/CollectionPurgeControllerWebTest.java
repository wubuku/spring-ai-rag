package com.springairag.core.controller;

import com.springairag.api.dto.CollectionPurgeApplyRequest;
import com.springairag.api.dto.CollectionPurgePreviewResponse;
import com.springairag.api.dto.CollectionPurgeResultResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.CollectionPurgeService;
import com.springairag.core.service.RagCollectionService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagCollectionController.class)
@Import({
        GlobalExceptionHandler.class,
        ApiVersionConfig.class,
        CollectionPurgeControllerWebTest.RagPropertiesTestConfig.class
})
class CollectionPurgeControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagCollectionRepository collectionRepository;

    @MockitoBean
    private RagDocumentRepository documentRepository;

    @MockitoBean
    private RagCollectionService collectionService;

    @MockitoBean
    private CollectionIdentityResolver identityResolver;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private CollectionPurgeService purgeService;

    @TestConfiguration
    static class RagPropertiesTestConfig {
        @Bean
        com.springairag.core.config.RagProperties ragProperties() {
            return new com.springairag.core.config.RagProperties();
        }
    }

    @Test
    void previewRouteReturnsBodyFreeCountsAndConfirmationFields()
            throws Exception {
        UUID previewId = UUID.randomUUID();
        OffsetDateTime previewExpiresAt =
                OffsetDateTime.of(2026, 8, 27, 12, 15, 0, 0,
                        ZoneOffset.UTC);
        OffsetDateTime operationExpiresAt =
                previewExpiresAt.plusMinutes(45);
        when(purgeService.preview(eq("knowledge-a"), any()))
                .thenReturn(new CollectionPurgePreviewResponse(
                        previewId, 7L, "knowledge-a", 3, 5, "PREVIEWED",
                        2, 1, 1, 4, 2, 3, 6, 1, 2, 21, 1,
                        1, 2, 1, 1, 1, 1, 2, 2, 1, 1,
                        0, 0, 0, 0, 0,
                        "confirm-once", "f".repeat(64),
                        previewExpiresAt, operationExpiresAt));

        mockMvc.perform(post(
                        "/api/v1/rag/collections/by-key/purge/preview")
                        .queryParam("collectionKey", "knowledge-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewId")
                        .value(previewId.toString()))
                .andExpect(jsonPath("$.collectionKey")
                        .value("knowledge-a"))
                .andExpect(jsonPath("$.documentCount").value(2))
                .andExpect(jsonPath("$.feedbackCount").value(1))
                .andExpect(jsonPath("$.affectedChatSessionCount")
                        .value(1))
                .andExpect(jsonPath("$.confirmationToken")
                        .value("confirm-once"))
                .andExpect(jsonPath("$.fingerprint")
                        .value("f".repeat(64)))
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test
    void applyRouteValidatesAndForwardsFrozenPreviewContract()
            throws Exception {
        UUID previewId = UUID.randomUUID();
        CollectionPurgeResultResponse result =
                new CollectionPurgeResultResponse(
                        previewId, "RETIRED", 7L, "knowledge-a",
                        2, 1, 1,
                        LocalDateTime.of(2026, 8, 27, 12, 0),
                        LocalDateTime.of(2026, 8, 27, 12, 1),
                        5);
        when(purgeService.apply(any(), any())).thenReturn(result);

        mockMvc.perform(post("/api/v1/rag/collections/by-key/purge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "collectionKey": "knowledge-a",
                                  "previewId": "%s",
                                  "confirmationToken": "confirm-once",
                                  "fingerprint": "%s",
                                  "expectedCollectionVersion": 3,
                                  "expectedChatCommitFenceVersion": 5
                                }
                                """.formatted(
                                previewId, "f".repeat(64))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"))
                .andExpect(jsonPath("$.collectionId").value(7))
                .andExpect(jsonPath("$.collectionKey")
                        .value("knowledge-a"))
                .andExpect(jsonPath("$.purgedDocumentCount").value(2))
                .andExpect(jsonPath("$.collectionVersion").value(5));

        verify(purgeService).apply(
                eq(new CollectionPurgeApplyRequest(
                        "knowledge-a", previewId, "confirm-once",
                        "f".repeat(64), 3L, 5L)),
                any());

        mockMvc.perform(post("/api/v1/rag/collections/by-key/purge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "collectionKey": "",
                                  "previewId": "%s",
                                  "confirmationToken": "",
                                  "fingerprint": "",
                                  "expectedCollectionVersion": -1,
                                  "expectedChatCommitFenceVersion": -1
                                }
                                """.formatted(previewId)))
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentType("application/problem+json"))
                .andExpect(jsonPath("$.error")
                        .value("VALIDATION_FAILED"));
        verify(purgeService, times(1)).apply(any(), any());
    }

    @Test
    void businessErrorsUseStableRfc7807CodesAndStatuses()
            throws Exception {
        when(purgeService.preview(eq("busy-collection"), any()))
                .thenThrow(new RagException(
                        ErrorCode.COLLECTION_PURGE_CONFLICT,
                        "Collection has active work or Chat sessions"));

        mockMvc.perform(post(
                        "/api/v1/rag/collections/by-key/purge/preview")
                        .queryParam("collectionKey", "busy-collection"))
                .andExpect(status().isConflict())
                .andExpect(content()
                        .contentType("application/problem+json"))
                .andExpect(jsonPath("$.error")
                        .value("COLLECTION_PURGE_CONFLICT"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail")
                        .value("Collection has active work or Chat sessions"))
                .andExpect(jsonPath("$.instance")
                        .value("/api/v1/rag/collections/by-key/purge/preview"));

        when(purgeService.preview(eq("disabled-collection"), any()))
                .thenThrow(new RagException(
                        ErrorCode.COLLECTION_PURGE_DISABLED,
                        "Collection purge is disabled"));

        mockMvc.perform(post(
                        "/api/v1/rag/collections/by-key/purge/preview")
                        .queryParam("collectionKey",
                                "disabled-collection"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error")
                        .value("COLLECTION_PURGE_DISABLED"))
                .andExpect(jsonPath("$.status").value(503));
    }
}
