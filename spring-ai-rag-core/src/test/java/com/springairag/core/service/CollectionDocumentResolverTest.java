package com.springairag.core.service;

import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CollectionDocumentResolver unit tests — isolation semantics.
 */
class CollectionDocumentResolverTest {

    private RagDocumentRepository documentRepository;
    private CollectionDocumentResolver resolver;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        resolver = new CollectionDocumentResolver(documentRepository);
    }

    @Test
    @DisplayName("null collectionIds returns documentIds as-is")
    void noCollectionFilter_returnsDocumentIds() {
        List<Long> docs = List.of(1L, 2L);
        assertSame(docs, resolver.resolveDocumentIds(docs, null));
        assertNull(resolver.resolveDocumentIds(null, null));
        verifyNoInteractions(documentRepository);
    }

    @Test
    @DisplayName("empty collectionIds returns documentIds as-is")
    void emptyCollectionFilter_returnsDocumentIds() {
        List<Long> docs = List.of(1L);
        assertSame(docs, resolver.resolveDocumentIds(docs, List.of()));
        verifyNoInteractions(documentRepository);
    }

    @Test
    @DisplayName("collection with docs returns those document ids")
    void collectionWithDocs_returnsIds() {
        when(documentRepository.findIdsByCollectionIdIn(List.of(10L)))
                .thenReturn(List.of(1L, 2L, 3L));
        List<Long> resolved = resolver.resolveDocumentIds(null, List.of(10L));
        assertEquals(List.of(1L, 2L, 3L), resolved);
    }

    @Test
    @DisplayName("collection with zero docs returns empty list (not null) — isolation")
    void emptyCollection_returnsEmptyList() {
        when(documentRepository.findIdsByCollectionIdIn(List.of(999L)))
                .thenReturn(List.of());
        List<Long> resolved = resolver.resolveDocumentIds(null, List.of(999L));
        assertNotNull(resolved);
        assertTrue(resolved.isEmpty());
    }

    @Test
    @DisplayName("intersection of documentIds and collection membership")
    void intersection() {
        when(documentRepository.findIdsByCollectionIdIn(List.of(1L)))
                .thenReturn(List.of(10L, 20L, 30L));
        List<Long> resolved = resolver.resolveDocumentIds(List.of(20L, 40L), List.of(1L));
        assertEquals(List.of(20L), resolved);
    }

    @Test
    @DisplayName("hasCollectionFilter helper")
    void hasCollectionFilter() {
        assertFalse(CollectionDocumentResolver.hasCollectionFilter(null));
        assertFalse(CollectionDocumentResolver.hasCollectionFilter(List.of()));
        assertTrue(CollectionDocumentResolver.hasCollectionFilter(List.of(1L)));
    }
}
