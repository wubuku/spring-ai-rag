package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.observability.IntegrationObservationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
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
    void sharedLifecycleLockRequiresAnActiveCollection() {
        RagCollection collection = collection(7L, "customer:manual", false);
        when(repository.findByIdAndDeletedFalse(7L))
                .thenReturn(Optional.of(collection));
        when(repository.findByIdAndDeletedFalse(8L))
                .thenReturn(Optional.empty());

        assertEquals(collection, resolver.requireActiveForShare(7L));
        assertThrows(RagException.class,
                () -> resolver.requireActiveForShare(8L));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.requireActiveForShare(null));
    }

    @Test
    void activeWriteTokenUsesCollectionVersionCas() {
        RagCollection collection = collection(7L, "customer:manual", false);
        collection.setVersion(4L);
        when(repository.findByIdAndDeletedFalse(7L))
                .thenReturn(Optional.of(collection));
        when(repository.findActiveVersion(7L)).thenReturn(Optional.of(4L));
        when(repository.advanceActiveVersion(7L, 4L)).thenReturn(1);

        CollectionIdentityResolver.ActiveCollectionToken token =
                resolver.beginActiveWrite(7L);
        resolver.confirmActiveWrite(token);

        assertEquals(7L, token.collectionId());
        assertEquals(5L, token.version());
        verify(repository).advanceActiveVersion(7L, 4L);
    }

    @Test
    void repeatedReservationsReadTheCurrentDatabaseVersion() {
        // 导入等多文档写入会在同一事务里连续预留写位置。批量 UPDATE 不会刷新
        // 一级缓存中的实体版本，因此第二次预留必须从数据库读取新版本，而不是
        // 复用实体里过期的 version，否则会误报 409 CONCURRENT_MODIFICATION。
        RagCollection staleEntity = collection(9L, "import:target", false);
        staleEntity.setVersion(0L);
        when(repository.findByIdAndDeletedFalse(9L))
                .thenReturn(Optional.of(staleEntity));
        when(repository.findActiveVersion(9L))
                .thenReturn(Optional.of(0L), Optional.of(1L));
        when(repository.advanceActiveVersion(9L, 0L)).thenReturn(1);
        when(repository.advanceActiveVersion(9L, 1L)).thenReturn(1);

        CollectionIdentityResolver.ActiveCollectionToken first =
                resolver.beginActiveWrite(9L);
        CollectionIdentityResolver.ActiveCollectionToken second =
                resolver.beginActiveWrite(9L);

        assertEquals(1L, first.version());
        assertEquals(2L, second.version());
        assertEquals(0L, staleEntity.getVersion(),
                "批量更新不刷新实体，这正是必须读数据库的原因");
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
        when(repository.findAllByCollectionKeyInAndDeletedFalse(any()))
                .thenReturn(List.of(
                        collection(2L, "two", false),
                        collection(1L, "one", false)));

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
        when(repository.findAllByCollectionKeyInAndDeletedFalse(any()))
                .thenReturn(List.of(collection(2L, "two", false)));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveIds(List.of(1L), List.of("two")));
    }

    @Test
    void activeKeysUseOneBatchRepositoryCallAndPreserveOrder() {
        MockHttpServletRequest request = bindRequest();
        when(repository.findAllByCollectionKeyInAndDeletedFalse(any()))
                .thenReturn(List.of(
                        collection(1L, "one", false),
                        collection(2L, "two", false)));

        assertEquals(List.of(2L, 1L),
                resolver.resolveActiveIds(
                        null, List.of("two", "one", "two")));
        verify(repository).findAllByCollectionKeyInAndDeletedFalse(any());
        verify(repository, never())
                .findByCollectionKeyAndDeletedFalse(any());
        assertEquals(List.of(1L, 2L),
                IntegrationObservationContext.authorizedCollectionIds(request));
    }

    @Test
    void restrictedResolutionOnlyLoadsAllowedCollections() {
        MockHttpServletRequest request = bindRequest();
        when(repository.findAllById(List.of(2L, 4L))).thenReturn(List.of(
                collection(2L, "two", false),
                collection(4L, "four", false)));

        assertEquals(List.of(4L, 2L), resolver.resolveActiveIdsWithinAllowed(
                List.of("four", "two", "four"), List.of(2L, 4L)));
        assertEquals(List.of(2L, 4L),
                IntegrationObservationContext.authorizedCollectionIds(request));
        verify(repository, never()).findByCollectionKeyAndDeletedFalse("four");

        RequestContextHolder.resetRequestAttributes();
        request = bindRequest();
        RagException unauthorized = assertThrows(RagException.class,
                () -> resolver.resolveActiveIdsWithinAllowed(
                        List.of("outside"), List.of(2L, 4L)));
        assertEquals(ErrorCode.COLLECTION_NOT_FOUND, unauthorized.getErrorCodeEnum());
        assertEquals(List.of(),
                IntegrationObservationContext.authorizedCollectionIds(request));
    }

    @Test
    void mixedBatchScopeCapturesOnlyAfterIdsAndKeysAgree() {
        MockHttpServletRequest request = bindRequest();
        when(repository.findByIdAndDeletedFalse(1L))
                .thenReturn(Optional.of(collection(1L, "one", false)));
        when(repository.findAllByCollectionKeyInAndDeletedFalse(any()))
                .thenReturn(List.of(collection(2L, "two", false)));

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveIds(List.of(1L), List.of("two")));
        assertEquals(List.of(),
                IntegrationObservationContext.authorizedCollectionIds(request));
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

    private MockHttpServletRequest bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }
}
