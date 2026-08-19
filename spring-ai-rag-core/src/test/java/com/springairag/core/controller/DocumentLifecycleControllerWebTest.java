package com.springairag.core.controller;

import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagDocumentController.class)
@Import({
        GlobalExceptionHandler.class,
        ApiVersionConfig.class,
        DocumentLifecycleControllerWebTest.RagPropertiesTestConfig.class
})
class DocumentLifecycleControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentMutationService documentMutationService;
    @MockBean
    private RagDocumentRepository documentRepository;
    @MockBean
    private RagEmbeddingRepository embeddingRepository;
    @MockBean
    private RagCollectionRepository collectionRepository;
    @MockBean
    private DocumentEmbedService documentEmbedService;
    @MockBean
    private BatchDocumentService batchDocumentService;
    @MockBean
    private DocumentVersionService documentVersionService;
    @MockBean
    private EmbeddingProfileProvider embeddingProfileProvider;
    @MockBean
    private CollectionIdentityResolver collectionIdentityResolver;
    @MockBean
    private com.springairag.core.service.AuditLogService auditLogService;

    @TestConfiguration
    static class RagPropertiesTestConfig {
        @Bean
        com.springairag.core.config.RagProperties ragProperties() {
            return new com.springairag.core.config.RagProperties();
        }
    }

    @Test
    void patchRoutesCasAndEmbeddingPolicy() throws Exception {
        when(documentMutationService.updateLocal(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(mutation("UPDATED", 8, true, "ASYNC_QUEUED",
                        lifecycle("INDEXING")));

        mockMvc.perform(patch("/api/v1/rag/documents/41")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedDocumentRevision": 7,
                                  "content": "Updated searchable content",
                                  "embeddingPolicy": "ASYNC"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("UPDATED"))
                .andExpect(jsonPath("$.documentRevision").value(8))
                .andExpect(jsonPath("$.contentChanged").value(true))
                .andExpect(jsonPath("$.embeddingAction").value("ASYNC_QUEUED"))
                .andExpect(jsonPath("$.lifecycle.searchability").value("INDEXING"));

        ArgumentCaptor<com.springairag.api.dto.DocumentUpdateRequest> request =
                ArgumentCaptor.forClass(
                        com.springairag.api.dto.DocumentUpdateRequest.class);
        verify(documentMutationService).updateLocal(
                org.mockito.ArgumentMatchers.eq(41L), request.capture());
        assertEquals(7L, request.getValue().getExpectedDocumentRevision());
        assertEquals(com.springairag.api.enums.EmbeddingPolicy.ASYNC,
                request.getValue().getEmbeddingPolicy());
        assertTrue(request.getValue().isContentPresent());
    }

    @Test
    void disableAndRestoreRouteExpectedRevision() throws Exception {
        when(documentMutationService.disableLocal(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(mutation("DISABLED", 9, false, "NONE",
                        lifecycle("DISABLED")));
        when(documentMutationService.restoreLocal(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(mutation("RESTORED", 10, false, "NONE",
                        lifecycle("READY")));

        mockMvc.perform(post("/api/v1/rag/documents/41/disable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedDocumentRevision": 8}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("DISABLED"))
                .andExpect(jsonPath("$.lifecycle.searchability")
                        .value("DISABLED"));

        mockMvc.perform(post("/api/v1/rag/documents/41/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedDocumentRevision": 9,
                                  "embeddingPolicy": "ASYNC"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("RESTORED"))
                .andExpect(jsonPath("$.lifecycle.searchability")
                        .value("READY"));
    }

    @Test
    void permanentDeleteRequiresRevisionAndReturnsRemovedCount() throws Exception {
        RagDocument document = new RagDocument();
        document.setId(41L);
        document.setEnabled(true);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));

        mockMvc.perform(delete("/api/v1/rag/documents/41"))
                .andExpect(status().isBadRequest());

        when(documentMutationService.hardDeleteLocal(41L, 10L))
                .thenReturn(new DocumentMutationService.DeletedLocal(
                        41L, 10L, 3L));

        mockMvc.perform(delete("/api/v1/rag/documents/41")
                        .param("expectedDocumentRevision", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(41))
                .andExpect(jsonPath("$.documentRevision").value(10))
                .andExpect(jsonPath("$.embeddingsRemoved").value(3));
    }

    @Test
    void mutationRequestsRequirePositiveRevision() throws Exception {
        mockMvc.perform(patch("/api/v1/rag/documents/41")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Missing revision"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/rag/documents/41/disable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedDocumentRevision": 0}
                                """))
                .andExpect(status().isBadRequest());
    }

    private static DocumentMutationResponse mutation(
            String action,
            long revision,
            boolean contentChanged,
            String embeddingAction,
            DocumentLifecycleResponse lifecycle) {
        return new DocumentMutationResponse(
                41L, action, revision, (int) revision,
                contentChanged, false, false, embeddingAction,
                null, null, lifecycle);
    }

    private static DocumentLifecycleResponse lifecycle(
            String searchability) {
        return new DocumentLifecycleResponse(
                "DISABLED".equals(searchability) ? "DISABLED" : "ACTIVE",
                searchability,
                searchability,
                searchability,
                "bge-m3-1024",
                null,
                null,
                null,
                !"READY".equals(searchability)
                        && !"DISABLED".equals(searchability));
    }
}
