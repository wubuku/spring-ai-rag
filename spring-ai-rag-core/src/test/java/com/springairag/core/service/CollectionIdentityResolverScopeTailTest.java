package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CollectionIdentityResolver 限定作用域与键解析长尾（Batch 626，
 * JaCoCo 驱动）：requireIncludingDeleted 按键缺失消息、
 * beginActiveWrite 非法 id、requireActiveWithinAllowed 已清理
 * 套件拒绝、requireIncludingDeletedWithinAllowed 缺失拒绝、
 * resolveActiveKeyIds 非法键名/已清理键/未知键拒绝、
 * resolveActiveIdsWithinAllowed 各拒绝分支、mapKeys 空/缺失
 * legacy ID 投影、validatePair 非法键名。
 */
class CollectionIdentityResolverScopeTailTest {

    private RagCollectionRepository repository;
    private CollectionIdentityResolver resolver;

    private RagCollection collection(long id, String key,
                                     LocalDateTime purgedAt, Boolean deleted) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        collection.setPurgedAt(purgedAt);
        collection.setDeleted(deleted);
        return collection;
    }

    @BeforeEach
    void setUp() {
        repository = mock(RagCollectionRepository.class);
        resolver = new CollectionIdentityResolver(repository);
    }

    @Test
    void requireIncludingDeletedByKeyReportsCollectionKey() {
        when(repository.findAllById(anySet())).thenReturn(List.of());

        RagException error = assertThrows(RagException.class,
                () -> resolver.requireIncludingDeleted(null, "kb-x"));
        assertEquals(ErrorCode.COLLECTION_NOT_FOUND,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("collectionKey=kb-x"));
    }

    @Test
    void beginActiveWriteRejectsNullAndNonPositiveIds() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.beginActiveWrite(null));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.beginActiveWrite(0L));
    }

    @Test
    void requireActiveWithinAllowedRejectsRetiredCollection() {
        RagCollection retired = collection(
                7L, "kb-7", LocalDateTime.now(), true);
        when(repository.findAllById(anySet())).thenReturn(List.of(retired));

        RagException error = assertThrows(RagException.class,
                () -> resolver.requireActiveWithinAllowed("kb-7", List.of(7L)));
        assertEquals(ErrorCode.COLLECTION_ALREADY_RETIRED,
                error.getErrorCodeEnum());
    }

    @Test
    void requireIncludingDeletedWithinAllowedRejectsMissingCollection() {
        when(repository.findAllById(anySet())).thenReturn(List.of());

        RagException error = assertThrows(RagException.class,
                () -> resolver.requireIncludingDeletedWithinAllowed(
                        "kb-x", List.of(7L)));
        assertEquals(ErrorCode.COLLECTION_NOT_FOUND,
                error.getErrorCodeEnum());
    }

    @Test
    void resolveActiveKeyIdsRejectsInvalidKeyName() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveIds(null, List.of("bad key!")));
    }

    @Test
    void resolveActiveKeyIdsRejectsRetiredThenUnknownKeys() {
        RagCollection retired = collection(
                7L, "kb-retired", LocalDateTime.now(), null);
        when(repository.findAllByCollectionKeyInAndDeletedFalse(anySet()))
                .thenReturn(List.of());
        when(repository.findByCollectionKey("kb-retired"))
                .thenReturn(Optional.of(retired));
        when(repository.findByCollectionKey("kb-ghost"))
                .thenReturn(Optional.empty());

        RagException retiredError = assertThrows(RagException.class,
                () -> resolver.resolveActiveIds(null, List.of("kb-retired")));
        assertEquals(ErrorCode.COLLECTION_ALREADY_RETIRED,
                retiredError.getErrorCodeEnum());

        RagException unknown = assertThrows(RagException.class,
                () -> resolver.resolveActiveIds(null, List.of("kb-ghost")));
        assertEquals(ErrorCode.COLLECTION_NOT_FOUND,
                unknown.getErrorCodeEnum());
    }

    @Test
    void resolveActiveIdsWithinAllowedRejectsInvalidAndUnknownKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveIdsWithinAllowed(
                        List.of("bad key!"), List.of(7L)));

        RagCollection retired = collection(
                8L, "kb-old", null, Boolean.TRUE);
        retired.setPurgedAt(LocalDateTime.now());
        when(repository.findAllById(anySet())).thenReturn(List.of(retired));

        RagException unknown = assertThrows(RagException.class,
                () -> resolver.resolveActiveIdsWithinAllowed(
                        List.of("kb-gone"), List.of(7L)));
        assertEquals(ErrorCode.COLLECTION_NOT_FOUND,
                unknown.getErrorCodeEnum());
    }

    @Test
    void resolveActiveIdsWithinAllowedRejectsEmptyScope() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveActiveIdsWithinAllowed(
                        List.of(), List.of(7L)));
    }

    @Test
    void mapKeysReturnsEmptyForNullAndMissingLegacyIds() {
        assertTrue(resolver.mapKeys(null).isEmpty());
        assertTrue(resolver.mapKeys(List.of()).isEmpty());

        RagCollection known = collection(7L, "kb-known", null, null);
        when(repository.findAllById(anySet())).thenReturn(List.of(known));

        var keys = resolver.mapKeys(List.of(7L, 99L));
        assertEquals(1, keys.size());
        assertEquals("kb-known", keys.get(7L));
    }

    @Test
    void validatePairRejectsInvalidKeyName() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.requireActive(null, "bad key!"));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.requireActive(0L, null));
    }

    @Test
    void requireActiveByIdRejectsRetiredCollection() {
        RagCollection retired = collection(
                9L, "kb-9", LocalDateTime.now(), false);
        when(repository.findByIdAndDeletedFalse(9L))
                .thenReturn(Optional.empty());
        when(repository.findById(9L)).thenReturn(Optional.of(retired));

        RagException error = assertThrows(RagException.class,
                () -> resolver.requireActive(9L, null));
        assertEquals(ErrorCode.COLLECTION_ALREADY_RETIRED,
                error.getErrorCodeEnum());
    }
}
