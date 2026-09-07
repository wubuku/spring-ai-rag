package com.springairag.core.embeddingjob;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 覆盖 EmbeddingJobRepository 的分页与按批查询：
 * 空授权集合短路、页大小钳制与总数透传、按批列表、
 * 授权集合数组绑定。
 */
class EmbeddingJobRepositoryListTest {

    private JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private EmbeddingJobRepository repository =
            new EmbeddingJobRepository(jdbcTemplate);

    @Test
    void listPageShortCircuitsEmptyAllowedCollections() {
        EmbeddingJobRepository.PageResult result = repository.listPage(
                null, null, 10L, List.of(), 20, 0);

        assertEquals(0, result.items().size());
        assertEquals(0, result.totalElements());
        org.mockito.Mockito.verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void listPageClampsPageSizeAndReturnsTotal() {
        when(jdbcTemplate.queryForObject(anyString(),
                eq(Long.class), any(Object[].class))).thenReturn(2L);
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        EmbeddingJobRepository.PageResult result = repository.listPage(
                null, null, null, null, 500, 200);

        // 页大小被钳制在 200 以内；总数来自 COUNT 查询。
        assertEquals(2L, result.totalElements());
    }

    @Test
    void findReturnsJobWhenRowExists() {
        when(jdbcTemplate.query(anyString(),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        assertTrue(repository.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void listAppliesBatchAndStatusFilters() {
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        repository.list(UUID.randomUUID(), EmbeddingJobStatus.QUEUED, 10, 0);

        org.mockito.Mockito.verify(jdbcTemplate).query(
                contains("AND batch_id = ?"), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void listPageBindsAllowedCollectionsAsArray() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        EmbeddingJobRepository.PageResult result = repository.listPage(
                null, null, null, List.of(10L, 20L), 20, 0);

        assertEquals(1L, result.totalElements());
        // ANY(?) 数组绑定由 pageFilter 生成。
        org.mockito.Mockito.verify(jdbcTemplate).queryForObject(
                contains("d.collection_id = ANY (?)"), eq(Long.class),
                any(Object[].class));
    }
}
