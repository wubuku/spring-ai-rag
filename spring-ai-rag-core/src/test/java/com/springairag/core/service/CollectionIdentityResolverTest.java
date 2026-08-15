package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class CollectionIdentityResolverTest {

    @Mock
    private RagCollectionRepository repository;

    private CollectionIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CollectionIdentityResolver(repository);
    }

    @Test
    void resolvesActiveCollectionByKeyOrId() {
        RagCollection collection = collection(7L, "customer:manual", false);
        when(repository.findByCollectionKeyAndDeletedFalse("customer:manual"))
                .thenReturn(Optional.of(collection));
        when(repository.findByIdAndDeletedFalse(7L))
                .thenReturn(Optional.of(collection));

        assertEquals(7L, resolver.resolveActiveId(null, "customer:manual"));
        assertEquals(7L, resolver.resolveActiveId(7L, null));
    }

    @Test
    void acceptsMatchingIdAndKeyAndRejectsMismatch() {
        RagCollection collection = collection(7L, "customer:manual", false);
        when(repository.findByCollectionKeyAndDeletedFalse("customer:manual"))
                .thenReturn(Optional.of(collection));

        assertEquals(7L, resolver.resolveActiveId(7L, "customer:manual"));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveId(8L, "customer:manual"));
    }

    @Test
    void unknownAndSoftDeletedKeysAreNotActive() {
        when(repository.findByCollectionKeyAndDeletedFalse("missing"))
                .thenReturn(Optional.empty());
        RagException missing = assertThrows(RagException.class,
                () -> resolver.requireActive(null, "missing"));
        assertEquals(ErrorCode.COLLECTION_NOT_FOUND, missing.getErrorCodeEnum());

        RagCollection deleted = collection(9L, "deleted", true);
        when(repository.findByCollectionKeyAndDeletedFalse("deleted"))
                .thenReturn(Optional.empty());
        when(repository.findByCollectionKey("deleted"))
                .thenReturn(Optional.of(deleted));

        assertTrue(resolver.findActive(null, "deleted").isEmpty());
        assertEquals(9L, resolver.resolveIncludingDeletedId(null, "deleted"));
    }

    @Test
    void listResolutionComparesSetsInsteadOfPositions() {
        when(repository.findByIdAndDeletedFalse(1L))
                .thenReturn(Optional.of(collection(1L, "one", false)));
        when(repository.findByIdAndDeletedFalse(2L))
                .thenReturn(Optional.of(collection(2L, "two", false)));
        when(repository.findByCollectionKeyAndDeletedFalse("two"))
                .thenReturn(Optional.of(collection(2L, "two", false)));
        when(repository.findByCollectionKeyAndDeletedFalse("one"))
                .thenReturn(Optional.of(collection(1L, "one", false)));

        assertEquals(List.of(2L, 1L), resolver.resolveActiveIds(
                List.of(1L, 2L), List.of("two", "one")));
    }

    @Test
    void listResolutionRejectsEmptyInvalidAndMismatchedScopes() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveIds(List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveIds(null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveIds(null, List.of("has space")));

        when(repository.findByIdAndDeletedFalse(1L))
                .thenReturn(Optional.of(collection(1L, "one", false)));
        when(repository.findByCollectionKeyAndDeletedFalse("two"))
                .thenReturn(Optional.of(collection(2L, "two", false)));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveIds(List.of(1L), List.of("two")));
    }

    @Test
    void restrictedResolutionOnlyLoadsAllowedCollections() {
        when(repository.findAllById(List.of(2L, 4L))).thenReturn(List.of(
                collection(2L, "two", false),
                collection(4L, "four", false)));

        assertEquals(List.of(4L, 2L), resolver.resolveActiveIdsWithinAllowed(
                List.of("four", "two", "four"), List.of(2L, 4L)));
        verify(repository, never()).findByCollectionKeyAndDeletedFalse("four");

        RagException unauthorized = assertThrows(RagException.class,
                () -> resolver.resolveActiveIdsWithinAllowed(
                        List.of("outside"), List.of(2L, 4L)));
        assertEquals(ErrorCode.COLLECTION_NOT_FOUND, unauthorized.getErrorCodeEnum());
    }

    @Test
    void restrictedSingleLookupDoesNotQueryGlobalKeyIndex() {
        RagCollection active = collection(2L, "active", false);
        RagCollection deleted = collection(4L, "deleted", true);
        when(repository.findAllById(any())).thenReturn(List.of(active, deleted));

        assertEquals(active, resolver.requireActiveWithinAllowed(
                "active", List.of(2L, 4L)));
        assertEquals(deleted, resolver.requireIncludingDeletedWithinAllowed(
                "deleted", List.of(2L, 4L)));
        assertThrows(RagException.class,
                () -> resolver.requireActiveWithinAllowed(
                        "deleted", List.of(2L, 4L)));
        verify(repository, never()).findByCollectionKey("deleted");
        verify(repository, never()).findByCollectionKeyAndDeletedFalse("active");
    }

    @Test
    void mapsKeysInOneRepositoryCall() {
        // 解析器会将输入去重为 Set，再按 Repository 的 Iterable 契约查询。
        when(repository.findAllById(any())).thenReturn(List.of(
                collection(4L, "four", false),
                collection(2L, "two", false)));

        Map<Long, String> result = resolver.mapKeys(List.of(2L, 4L));

        assertEquals(Map.of(2L, "two", 4L, "four"), result);
        assertEquals(List.of(2L, 4L), result.keySet().stream().toList());
        verify(repository).findAllById(any());
    }

    private RagCollection collection(Long id, String key, boolean deleted) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        collection.setName(key);
        collection.setDeleted(deleted);
        return collection;
    }
}
