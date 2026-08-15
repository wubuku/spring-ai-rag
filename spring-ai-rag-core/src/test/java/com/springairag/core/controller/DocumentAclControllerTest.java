package com.springairag.core.controller;

import com.springairag.api.dto.DocumentRequest;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentAclControllerTest {

    private RagDocumentRepository documentRepository;
    private RagCollectionRepository collectionRepository;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        collectionRepository = mock(RagCollectionRepository.class);
        EmbeddingProfileProvider profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                1L, "test-profile", "test", "test-model", "v1",
                1024, "COSINE", "PROVIDER_DEFAULT", true));
        controller = new RagDocumentController(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                collectionRepository,
                mock(DocumentEmbedService.class),
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                profileProvider,
                null);
        authenticateRestrictedKey(2L, 4L);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void restrictedKeyCannotReadDocumentFromAnotherCollection() {
        RagDocument document = new RagDocument();
        document.setId(10L);
        document.setCollectionId(9L);
        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));

        assertThrows(SecurityException.class, () -> controller.getDocument(10L));
    }

    @Test
    void restrictedDocumentListUsesAllowedCollectionQuery() {
        when(documentRepository.searchDocumentsByCollectionIds(
                eq(List.of(2L, 4L)), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        controller.listDocuments(
                0, 20, null, null, null, null, null, null, null);

        verify(documentRepository).searchDocumentsByCollectionIds(
                eq(List.of(2L, 4L)), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), any());
        verify(documentRepository, never()).searchDocuments(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void restrictedCreateByKeyDoesNotExposeGlobalCollectionExistence() {
        when(collectionRepository.findAllById(any())).thenReturn(List.of());
        DocumentRequest request = new DocumentRequest("Scoped document", "content");
        request.setCollectionKey("missing-or-unauthorized");

        assertThrows(SecurityException.class,
                () -> controller.createDocument(request));
        verify(collectionRepository, never())
                .findByCollectionKeyAndDeletedFalse("missing-or-unauthorized");
        verifyNoInteractions(documentRepository);
    }

    private void authenticateRestrictedKey(Long... ids) {
        RagApiKey key = new RagApiKey();
        key.setRole(ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds(String.join(",",
                java.util.Arrays.stream(ids).map(String::valueOf).toList()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY, key);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }
}
