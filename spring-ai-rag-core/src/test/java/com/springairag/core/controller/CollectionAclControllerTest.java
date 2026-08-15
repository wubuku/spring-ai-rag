package com.springairag.core.controller;

import com.springairag.api.dto.CollectionRequest;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.RagCollectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollectionAclControllerTest {

    private RagCollectionRepository collectionRepository;
    private RagCollectionController controller;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(RagCollectionRepository.class);
        RagDocumentRepository documentRepository = mock(RagDocumentRepository.class);
        controller = new RagCollectionController(
                collectionRepository,
                documentRepository,
                mock(RagCollectionService.class),
                null);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void restrictedKeyListsOnlyAllowedCollections() {
        authenticateRestrictedKey(2L, 4L);
        when(collectionRepository.searchCollectionsByIds(
                eq(List.of(2L, 4L)), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        controller.list(0, 20, null, null);

        verify(collectionRepository).searchCollectionsByIds(
                eq(List.of(2L, 4L)), isNull(), isNull(), any());
        verify(collectionRepository, never()).searchCollections(any(), any(), any());
    }

    @Test
    void restrictedKeyCannotReadAnotherCollection() {
        authenticateRestrictedKey(2L);

        assertThrows(SecurityException.class, () -> controller.getById(9L));
        verifyNoInteractions(collectionRepository);
    }

    @Test
    void restrictedByKeyLookupDoesNotExposeGlobalExistence() {
        authenticateRestrictedKey(2L);
        when(collectionRepository.findAllById(any())).thenReturn(List.of());

        assertThrows(SecurityException.class,
                () -> controller.getByKey("missing-or-unauthorized"));
        verify(collectionRepository, never())
                .findByCollectionKeyAndDeletedFalse("missing-or-unauthorized");
    }

    @Test
    void restrictedByKeyLookupAllowsAnAuthorizedCollection() {
        authenticateRestrictedKey(2L);
        RagCollection collection = new RagCollection();
        collection.setId(2L);
        collection.setCollectionKey("allowed");
        collection.setName("Allowed");
        collection.setDeleted(false);
        when(collectionRepository.findAllById(any())).thenReturn(List.of(collection));

        controller.getByKey("allowed");

        verify(collectionRepository, never())
                .findByCollectionKeyAndDeletedFalse("allowed");
    }

    @Test
    void restrictedKeyCannotCreateCollection() {
        authenticateRestrictedKey(2L);
        CollectionRequest request = new CollectionRequest();
        request.setName("Denied");

        assertThrows(SecurityException.class, () -> controller.create(request));
        verifyNoInteractions(collectionRepository);
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
