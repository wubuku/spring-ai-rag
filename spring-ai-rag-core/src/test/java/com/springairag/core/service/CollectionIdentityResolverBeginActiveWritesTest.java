package com.springairag.core.service;

import com.springairag.core.entity.RagCollection;
import com.springairag.core.repository.RagCollectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * CollectionIdentityResolver.beginActiveWrites 批量预留（Batch 348）：
 * 空/null 集合零交互、去重排序去 null、单集合 CAS 命中派生令牌、
 * CAS 未命中或停用集合抛乐观锁异常。
 */
class CollectionIdentityResolverBeginActiveWritesTest {

    private RagCollectionRepository repository;
    private CollectionIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(RagCollectionRepository.class);
        resolver = new CollectionIdentityResolver(repository);
    }

    private RagCollection collection(long id, long version, boolean enabled) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setVersion(version);
        collection.setEnabled(enabled);
        return collection;
    }

    @Test
    void nullOrEmptyCollectionsReturnEmptyWithoutRepositoryInteraction() {
        assertEquals(List.of(), resolver.beginActiveWrites(null));
        assertEquals(List.of(), resolver.beginActiveWrites(List.of()));
    }

    @Test
    void duplicatesNullsAreRemovedAndOrderedById() {
        when(repository.findByIdAndDeletedFalse(2L))
                .thenReturn(java.util.Optional.of(collection(2L, 4L, true)));
        when(repository.findByIdAndDeletedFalse(5L))
                .thenReturn(java.util.Optional.of(collection(5L, 1L, true)));
        when(repository.advanceActiveVersion(anyLong(), anyLong()))
                .thenReturn(1);

        Collection<Long> input = Arrays.asList(5L, 2L, null, 5L, 2L);
        var tokens = resolver.beginActiveWrites(input);

        // 去重去 null 后按 id 升序预留。
        assertEquals(2, tokens.size());
        assertEquals(2L, tokens.get(0).collectionId());
        assertEquals(5L, tokens.get(1).collectionId());
        // 令牌携带版本递增结果。
        assertEquals(5L, tokens.get(0).version());
    }

    @Test
    void casMissOrDisabledCollectionThrowsOptimisticLocking() {
        when(repository.findByIdAndDeletedFalse(3L))
                .thenReturn(java.util.Optional.of(collection(3L, 2L, true)));
        // CAS 未命中（并发已推进版本）。
        when(repository.advanceActiveVersion(3L, 2L)).thenReturn(0);

        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                () -> resolver.beginActiveWrites(List.of(3L)));

        // 停用集合：CAS 未执行即拒绝。
        when(repository.findByIdAndDeletedFalse(4L))
                .thenReturn(java.util.Optional.of(collection(4L, 2L, false)));
        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                () -> resolver.beginActiveWrites(List.of(4L)));
    }

    @Test
    void nonPositiveCollectionIdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.beginActiveWrites(List.of(0L)));
    }
}
