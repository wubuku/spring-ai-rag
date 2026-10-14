package com.springairag.core.controller;

import com.springairag.core.config.RagChunkProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.DocumentChunkingService;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.versioning.ApiVersionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DocumentChunkPreviewController.class, properties = {
        "rag.chunk.profile=bounded-v3", "rag.chunk.default-chunk-size=100",
        "rag.chunk.min-chunk-size=10", "rag.chunk.default-chunk-overlap=10",
        "rag.chunk.preview-max-chars=1000"})
@Import({ApiVersionConfig.class, GlobalExceptionHandler.class,
        DocumentChunkPreviewControllerTest.Config.class})
class DocumentChunkPreviewControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private RagDocumentRepository repository;

    @TestConfiguration
    static class Config {
        @Bean RagProperties properties() {
            RagProperties properties = new RagProperties();
            properties.getChunk().setProfile(RagChunkProperties.Profile.BOUNDED_V3);
            properties.getChunk().setDefaultChunkSize(100);
            properties.getChunk().setMinChunkSize(10);
            properties.getChunk().setDefaultChunkOverlap(10);
            properties.getChunk().setPreviewMaxChars(1000);
            return properties;
        }
        @Bean DocumentDerivationDescriptorProvider descriptor(RagProperties properties) {
            return new DocumentDerivationDescriptorProvider(properties);
        }
        @Bean DocumentChunkingService chunking(RagProperties properties, DocumentDerivationDescriptorProvider descriptor) {
            return new DocumentChunkingService(properties, descriptor);
        }
    }

    @Test
    void versionedRoutePaginatesSameProductionChunksWithoutWrites() throws Exception {
        when(repository.findById(41L)).thenReturn(Optional.of(document("x".repeat(500))));
        mvc.perform(get("/api/v1/rag/documents/41/chunks/preview").param("offset", "1").param("limit", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profile").value("bounded-v3"))
                .andExpect(jsonPath("$.documentRevision").value(1))
                .andExpect(jsonPath("$.chunks.length()").value(2))
                .andExpect(jsonPath("$.chunks[0].index").value(1))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.diagnostics.valid").value(true))
                .andExpect(jsonPath("$.diagnostics.coveredNonWhitespaceChars").value(500));
        verify(repository, never()).save(any());
    }

    @Test
    void invalidPagesMissingDocumentsAndPreviewLimitReturnSafeErrors() throws Exception {
        mvc.perform(get("/api/v1/rag/documents/41/chunks/preview").param("limit", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("CHUNK_PREVIEW_INVALID_PAGE"));
        mvc.perform(get("/api/v1/rag/documents/41/chunks/preview").param("offset", "-1"))
                .andExpect(status().isBadRequest());
        when(repository.findById(41L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/rag/documents/41/chunks/preview")).andExpect(status().isNotFound());
        when(repository.findById(41L)).thenReturn(Optional.of(document("SECRET_BODY_".repeat(100))));
        mvc.perform(get("/api/v1/rag/documents/41/chunks/preview"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("CHUNK_PREVIEW_INPUT_LIMIT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET_BODY_"))));
    }

    @Test
    void restrictedCollectionIsRejectedBeforeChunking() throws Exception {
        RagApiKey key = new RagApiKey();
        key.setRole(ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds("2");
        when(repository.findById(41L)).thenReturn(Optional.of(document("SECRET_BODY_".repeat(100))));
        mvc.perform(get("/api/v1/rag/documents/41/chunks/preview")
                .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY, key))
                .andExpect(status().isForbidden());
    }

    @Test
    void authorizedCollectionAndEmptyPageAreSupported() throws Exception {
        RagApiKey key = new RagApiKey();
        key.setRole(ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds("1");
        when(repository.findById(41L)).thenReturn(Optional.of(document("short")));
        mvc.perform(get("/api/v1/rag/documents/41/chunks/preview").param("offset", "2147483647")
                .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY, key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chunks.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    private RagDocument document(String content) {
        RagDocument document = new RagDocument();
        document.setId(41L);
        document.setCollectionId(1L);
        document.setContent(content);
        return document;
    }
}
