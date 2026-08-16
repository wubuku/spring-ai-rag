package com.springairag.core.controller;

import com.springairag.api.dto.ExternalDocumentDeleteResponse;
import com.springairag.api.dto.ExternalDocumentUpsertResponse;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.service.ExternalDocumentService;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagDocumentController.class)
@Import({
        GlobalExceptionHandler.class,
        ApiVersionConfig.class,
        ExternalDocumentControllerWebTest.RagPropertiesTestConfig.class
})
class ExternalDocumentControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExternalDocumentService externalDocumentService;
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
    void upsertRoutesAndSerializesEmbeddingState() throws Exception {
        when(externalDocumentService.upsert(any()))
                .thenReturn(new ExternalDocumentUpsertResponse(
                        41L,
                        "customer-42:manual:v1",
                        "doc-1",
                        "rev-2",
                        "UPDATED",
                        true,
                        2,
                        "COMPLETED",
                        "bge-m3",
                        true,
                        "COMPLETED",
                        null,
                        null,
                        null));

        mockMvc.perform(post("/api/v1/rag/documents/upsert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "collectionKey": "customer-42:manual:v1",
                                  "externalId": "doc-1",
                                  "sourceRevision": "rev-2",
                                  "expectedSourceRevision": "rev-1",
                                  "title": "Updated",
                                  "content": "Updated content",
                                  "embed": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(41))
                .andExpect(jsonPath("$.action").value("UPDATED"))
                .andExpect(jsonPath("$.embeddingFresh").value(true));
    }

    @Test
    void blankSourceRevisionIsRejectedBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/rag/documents/upsert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "collectionKey": "customer-42:manual:v1",
                                  "externalId": "doc-1",
                                  "sourceRevision": " ",
                                  "title": "Title",
                                  "content": "Content"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(externalDocumentService, never()).upsert(any());
    }

    @Test
    void revisionConflictIsRenderedAs409Problem() throws Exception {
        when(externalDocumentService.upsert(any()))
                .thenThrow(new DocumentRevisionConflictException(
                        "expectedSourceRevision does not match"));

        mockMvc.perform(post("/api/v1/rag/documents/upsert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "collectionKey": "customer-42:manual:v1",
                                  "externalId": "doc-1",
                                  "sourceRevision": "rev-3",
                                  "title": "Title",
                                  "content": "Content"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DOCUMENT_REVISION_CONFLICT"));
    }

    @Test
    void deleteRoutesExpectedRevisionAndTombstoneState() throws Exception {
        when(externalDocumentService.sourceDelete(
                "customer-42:manual:v1", "doc-1", "rev-4", "rev-3"))
                .thenReturn(new ExternalDocumentDeleteResponse(
                        41L,
                        "customer-42:manual:v1",
                        "doc-1",
                        "rev-4",
                        "DELETED",
                        3,
                        false,
                        null,
                        null,
                        null));

        mockMvc.perform(delete("/api/v1/rag/documents/by-external-id")
                        .param("collectionKey", "customer-42:manual:v1")
                        .param("externalId", "doc-1")
                        .param("sourceRevision", "rev-4")
                        .param("expectedSourceRevision", "rev-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("DELETED"))
                .andExpect(jsonPath("$.enabled").value(false));
    }
}
