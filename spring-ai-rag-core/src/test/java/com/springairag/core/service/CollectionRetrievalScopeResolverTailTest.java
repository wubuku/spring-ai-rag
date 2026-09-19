package com.springairag.core.service;

import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CollectionRetrievalScopeResolver 授权长尾（Batch 528，JaCoCo 驱
 * 动）：受限调用方的 ids 授权集合校验、ids/keys 交集一致性、keys
 * 解析的 RagException 透传与包装、validateIds/validateKeys 边界、
 * 空 documentType 拒绝。
 */
class CollectionRetrievalScopeResolverTailTest {

    private CollectionIdentityResolver identityResolver;
    private CollectionRetrievalScopeResolver resolver;

    @BeforeEach
    void setUp() {
        identityResolver = mock(CollectionIdentityResolver.class);
        resolver = new CollectionRetrievalScopeResolver(identityResolver);
    }

    /** 受限调用方：仅授权集合 10 与 11。 */
    private static ApiAccessPolicy restrictedCaller() {
        return new ApiAccessPolicy() {
            @Override public String getPrincipalId() { return "rag_p_1"; }
            @Override public String getCredentialId() { return "rag_k_1"; }
            @Override public ApiKeyRole getRole() { return ApiKeyRole.NORMAL; }
            @Override public String getAllowedCollectionIds() { return "10,11"; }
            @Override public LocalDateTime getExpiresAt() { return null; }
        };
    }

    private com.springairag.core.entity.RagCollection collection(long id) {
        var value = new com.springairag.core.entity.RagCollection();
        value.setId(id);
        value.setCollectionKey("kb-" + id);
        value.setEnabled(true);
        return value;
    }

    @Test
    void nullModeWithIdsInfersSelectedCollections() {
        when(identityResolver.requireActive(10L, null))
                .thenReturn(collection(10));

        RetrievalScope scope = resolver.resolve(
                null, List.of(10L), null, List.of(5L), null, null);

        assertEquals(RetrievalScope.CollectionFilter.SELECTED,
                scope.collectionFilter());
        assertEquals(List.of(10L), scope.collectionIds());
    }

    @Test
    void restrictedCallerAuthorizesIdsWithinAllowList() {
        when(identityResolver.requireActive(10L, null))
                .thenReturn(collection(10));

        RetrievalScope scope = resolver.resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                List.of(10L), null, null, null, restrictedCaller());

        assertEquals(List.of(10L), scope.collectionIds());
    }

    @Test
    void restrictedCallerRejectsIdsOutsideAllowList() {
        var error = assertThrows(SecurityException.class,
                () -> resolver.resolve(
                        CollectionScopeMode.SELECTED_COLLECTIONS,
                        List.of(99L), null, null, null, restrictedCaller()));

        assertEquals("Collection is not authorized", error.getMessage());
    }

    @Test
    void keysOnlySelectionResolvesThroughIdentityResolver() {
        when(identityResolver.resolveActiveIds(isNull(), eq(List.of("kb-10"))))
                .thenReturn(List.of(10L));

        RetrievalScope scope = resolver.resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                null, List.of("kb-10"), null, null, null);

        assertEquals(List.of(10L), scope.collectionIds());
    }

    @Test
    void idsAndKeysMismatchIsRejected() {
        when(identityResolver.requireActive(10L, null))
                .thenReturn(collection(10));
        when(identityResolver.resolveActiveIds(isNull(), anyList()))
                .thenReturn(List.of(11L));

        var error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(
                        CollectionScopeMode.SELECTED_COLLECTIONS,
                        List.of(10L), List.of("kb-11"), null, null, null));

        assertEquals(
                "collectionIds and collectionKeys identify different collections",
                error.getMessage());
    }

    @Test
    void restrictedKeysResolutionWrapsNonRetiredFailures() {
        when(identityResolver.resolveActiveIdsWithinAllowed(
                anyList(), any())).thenThrow(new RagException(
                ErrorCode.NOT_FOUND, "missing"));

        var error = assertThrows(SecurityException.class,
                () -> resolver.resolve(
                        CollectionScopeMode.SELECTED_COLLECTIONS,
                        null, List.of("kb-10"), null, null,
                        restrictedCaller()));

        assertEquals("Collection is not authorized", error.getMessage());
    }

    @Test
    void unrestrictedKeysResolutionRethrowsRagException() {
        when(identityResolver.resolveActiveIds(isNull(), anyList()))
                .thenThrow(new RagException(
                        ErrorCode.NOT_FOUND, "missing"));

        assertThrows(RagException.class,
                () -> resolver.resolve(
                        CollectionScopeMode.SELECTED_COLLECTIONS,
                        null, List.of("kb-10"), null, null, null));
    }

    @Test
    void nonPositiveDocumentIdsAreRejected() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(
                        CollectionScopeMode.CALLER_VISIBLE,
                        null, null, List.of(0L), null, null));

        assertEquals("documentIds must contain positive IDs",
                error.getMessage());
    }

    @Test
    void emptyKeysAreRejected() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(
                        CollectionScopeMode.SELECTED_COLLECTIONS,
                        null, List.of(), null, null, null));

        assertEquals("Collection scope must not be empty",
                error.getMessage());
    }

    @Test
    void oversizeKeysAreRejected() {
        List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            keys.add("kb-" + i);
        }

        var error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(
                        CollectionScopeMode.SELECTED_COLLECTIONS,
                        null, keys, null, null, null));

        assertEquals("collectionKeys must not contain more than 100 items",
                error.getMessage());
    }

    @Test
    void invalidKeyCharactersAreRejected() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(
                        CollectionScopeMode.SELECTED_COLLECTIONS,
                        null, List.of("kb bad"), null, null, null));

        assertEquals(
                "collectionKey must contain 1-128 visible ASCII characters",
                error.getMessage());
    }

    @Test
    void blankDocumentTypeIsRejected() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(
                        CollectionScopeMode.CALLER_VISIBLE,
                        null, null, null, "  ", null));

        assertEquals("documentType must not be blank",
                error.getMessage());
    }
}
