package com.springairag.core.service;

import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.exception.RagException;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionRetrievalScopeResolverTest {

    private CollectionIdentityResolver identityResolver;
    private CollectionRetrievalScopeResolver resolver;

    @BeforeEach
    void setUp() {
        identityResolver = mock(CollectionIdentityResolver.class);
        resolver = new CollectionRetrievalScopeResolver(identityResolver);
    }

    @Test
    void omittedUnrestrictedIsCallerVisible() {
        RetrievalScope scope = resolver.resolve(
                null, null, null, null, null, null);

        assertEquals(RetrievalScope.CollectionFilter.NONE,
                scope.collectionFilter());
    }

    @Test
    void omittedRestrictedUsesAllowList() {
        RetrievalScope scope = resolver.resolve(
                null, null, null, null, null, restricted(2L, 4L));

        assertEquals(RetrievalScope.CollectionFilter.SELECTED,
                scope.collectionFilter());
        assertEquals(List.of(2L, 4L), scope.collectionIds());
    }

    @Test
    void anyCollectionUsesAssignedOrRestrictedScope() {
        RetrievalScope unrestricted = resolver.resolve(
                CollectionScopeMode.ANY_COLLECTION,
                null, null, null, null, null);
        RetrievalScope restricted = resolver.resolve(
                CollectionScopeMode.ANY_COLLECTION,
                null, null, null, null, restricted(2L));

        assertEquals(RetrievalScope.CollectionFilter.ANY_ASSIGNED,
                unrestricted.collectionFilter());
        assertEquals(List.of(2L), restricted.collectionIds());
    }

    @Test
    void selectedKeysResolveAndKeepDocumentIntersection() {
        when(identityResolver.resolveActiveIds(
                null, List.of("b", "a")))
                .thenReturn(List.of(2L, 1L));

        RetrievalScope scope = resolver.resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                null,
                List.of("b", "a", "b"),
                List.of(11L, 10L, 11L),
                null,
                null);

        assertEquals(List.of(2L, 1L), scope.collectionIds());
        assertEquals(List.of(11L, 10L), scope.documentIds());
    }

    @Test
    void restrictedKeysNeverUseGlobalLookup() {
        when(identityResolver.resolveActiveIdsWithinAllowed(
                List.of("allowed"), Set.of(2L, 4L)))
                .thenReturn(List.of(2L));

        RetrievalScope scope = resolver.resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                null, List.of("allowed"), null, null,
                restricted(2L, 4L));

        assertEquals(List.of(2L), scope.collectionIds());
        verify(identityResolver, never()).resolveActiveIds(
                null, List.of("allowed"));
    }

    @Test
    void restrictedUnknownKeyIsForbidden() {
        when(identityResolver.resolveActiveIdsWithinAllowed(
                List.of("outside"), Set.of(2L, 4L)))
                .thenThrow(new RagException(
                        ErrorCode.COLLECTION_NOT_FOUND, "missing"));

        assertThrows(SecurityException.class, () -> resolver.resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                null, List.of("outside"), null, null,
                restricted(2L, 4L)));
    }

    @Test
    void explicitUnknownAndRetiredNumericIdsAreRejected() {
        when(identityResolver.requireActive(999L, null))
                .thenThrow(new RagException(
                        ErrorCode.COLLECTION_NOT_FOUND, "missing"));
        when(identityResolver.requireActive(998L, null))
                .thenThrow(new RagException(
                        ErrorCode.COLLECTION_ALREADY_RETIRED, "retired"));

        RagException missing = assertThrows(RagException.class,
                () -> resolver.resolve(
                        CollectionScopeMode.SELECTED_COLLECTIONS,
                        List.of(999L), null, null, null, null));
        RagException retired = assertThrows(RagException.class,
                () -> resolver.resolve(
                        CollectionScopeMode.SELECTED_COLLECTIONS,
                        List.of(998L), null, null, null, null));

        assertEquals(ErrorCode.COLLECTION_NOT_FOUND,
                missing.getErrorCodeEnum());
        assertEquals(ErrorCode.COLLECTION_ALREADY_RETIRED,
                retired.getErrorCodeEnum());
    }

    @Test
    void rejectsConflictsEmptyScopesAndLimits() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                CollectionScopeMode.CALLER_VISIBLE,
                null, List.of("a"), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                null, List.of(), null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                null,
                java.util.Collections.nCopies(101, 1L),
                null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                null, null, null,
                java.util.Collections.nCopies(1001, 1L),
                null, null));
    }

    @Test
    void rejectsMismatchedIdsAndKeys() {
        com.springairag.core.entity.RagCollection one =
                new com.springairag.core.entity.RagCollection();
        one.setId(1L);
        when(identityResolver.requireActive(1L, null)).thenReturn(one);
        when(identityResolver.resolveActiveIds(
                null, List.of("two"))).thenReturn(List.of(2L));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(
                        CollectionScopeMode.SELECTED_COLLECTIONS,
                        List.of(1L), List.of("two"),
                        null, null, null));
        assertTrue(error.getMessage().contains("different collections"));
    }

    private RagApiKey restricted(Long... ids) {
        RagApiKey key = new RagApiKey();
        key.setRole(ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds(String.join(",",
                java.util.Arrays.stream(ids)
                        .map(String::valueOf)
                        .toList()));
        return key;
    }
}
