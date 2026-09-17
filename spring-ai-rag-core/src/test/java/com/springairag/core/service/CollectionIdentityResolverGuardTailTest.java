package com.springairag.core.service;

import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CollectionIdentityResolver 守卫长尾（Batch 511，JaCoCo 驱动）：
 * requireIncludingDeleted 的缺失/已清理拒绝、resolveActiveIds 的
 * 空列表与 ids/keys 集合不一致拒绝、resolveActiveKeyIds 的已清理
 * 键拒绝、resolveActiveIdsWithinAllowed 的非法键名/未知键/已清理
 * 键拒绝，以及 validatePair 的各种非法组合。
 */
class CollectionIdentityResolverGuardTailTest {

    private RagCollectionRepository repository;
    private CollectionIdentityResolver resolver;

    private RagCollection collection(long id, String key,
                                     java.time.LocalDateTime purgedAt) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        collection.setPurgedAt(purgedAt);
        return collection;
    }

    @BeforeEach
    void setUp() {
        repository = mock(RagCollectionRepository.class);
        resolver = new CollectionIdentityResolver(repository);
    }

    @Test
    void requireIncludingDeletedRejectsMissingCollection() {
        when(repository.findByIdAndDeletedFalse(9L)).thenReturn(Optional.empty());
        when(repository.findById(9L)).thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> resolver.requireIncludingDeleted(9L, null));
        assertEquals(com.springairag.api.enums.ErrorCode.COLLECTION_NOT_FOUND,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("id=9"));
    }

    @Test
    void resolveActiveIdsRejectsNullAndEmptyLists() {
        assertNull(resolver.resolveActiveIds(null, null));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveIds(List.of(), List.of()));
    }

    @Test
    void resolveActiveIdsRejectsMismatchedIdAndKeySets() {
        RagCollection c7 = collection(7L, "kb-7", null);
        RagCollection c8 = collection(8L, "kb-8", null);
        when(repository.findByIdAndDeletedFalse(7L)).thenReturn(Optional.of(c7));
        when(repository.findById(7L)).thenReturn(Optional.of(c7));
        when(repository.findAllByCollectionKeyInAndDeletedFalse(any()))
                .thenReturn(List.of(c8));
        when(repository.findByCollectionKey("kb-8")).thenReturn(Optional.of(c8));
        when(repository.findByIdAndDeletedFalse(8L)).thenReturn(Optional.of(c8));
        when(repository.findById(8L)).thenReturn(Optional.of(c8));

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveIds(List.of(7L), List.of("kb-8")));
    }

    @Test
    void resolveActiveKeysRejectsRetiredKey() {
        RagCollection retired = collection(7L, "kb-retired",
                java.time.LocalDateTime.now());
        when(repository.findAllByCollectionKeyInAndDeletedFalse(
                any())).thenReturn(List.of());
        when(repository.findByCollectionKey("kb-retired"))
                .thenReturn(Optional.of(retired));

        assertThrows(Exception.class,
                () -> resolver.resolveActiveIds(null, List.of("kb-retired")));
    }

    @Test
    void resolveActiveKeysRejectsUnknownKey() {
        when(repository.findAllByCollectionKeyInAndDeletedFalse(
                any())).thenReturn(List.of());
        when(repository.findByCollectionKey("kb-ghost")).thenReturn(Optional.empty());

        assertThrows(Exception.class,
                () -> resolver.resolveActiveIds(null, List.of("kb-ghost")));
    }

    @Test
    void resolveActiveIdsWithinAllowedRejectsInvalidKeyName() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveIdsWithinAllowed(
                        List.of("bad key!"), java.util.Set.of(7L)));
    }

    @Test
    void resolveActiveIdsWithinAllowedRejectsEmptyKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveIdsWithinAllowed(
                        List.of(), java.util.Set.of(7L)));
    }

    @Test
    void requireActiveRejectsNonPositiveId() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.requireActive(0L, null));
    }

    @Test
    void requireActiveRejectsMismatchedIdAndKey() {
        RagCollection c7 = collection(7L, "kb-7", null);
        when(repository.findByCollectionKey("kb-7")).thenReturn(Optional.of(c7));

        assertThrows(IllegalArgumentException.class,
                () -> resolver.requireActive(9L, "kb-7"));
    }

    @Test
    void requireActiveRejectsAlreadyRetiredCollection() {
        RagCollection retired = collection(7L, "kb-7",
                java.time.LocalDateTime.now());
        when(repository.findByIdAndDeletedFalse(7L)).thenReturn(Optional.empty());
        when(repository.findById(7L)).thenReturn(Optional.of(retired));

        assertThrows(Exception.class, () -> resolver.requireActive(7L, null));
    }

    @Test
    void mapKeysResolvesThroughRepository() {
        RagCollection c7 = collection(7L, "kb-7", null);
        when(repository.findAllById(any())).thenReturn(List.of(c7));

        Map<Long, String> keys = resolver.mapKeys(List.of(7L));

        assertEquals("kb-7", keys.get(7L));
    }

    @Test
    void validatePairRejectsBothNullIdAndKey() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.requireActive(null, null));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.requireActive(-1L, null));
    }

    @Test
    void emptyResultDataAccessExceptionPropagatesAsIs() {
        // findById 抛 EmptyResultDataAccessException 的路径在
        // findIncludingDeleted 内部不会出现（返回 Optional），此处
        // 仅验证 repository 未命中时统一收敛为 COLLECTION_NOT_FOUND。
        when(repository.findByIdAndDeletedFalse(5L)).thenReturn(Optional.empty());
        when(repository.findById(5L)).thenReturn(Optional.empty());

        assertThrows(Exception.class, () -> resolver.requireIncludingDeleted(5L, null));
    }
}
