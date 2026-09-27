package com.springairag.core.retrieval.fulltext;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * PgTrgmFulltextProvider isExcluded 兜底臂长尾（Batch 683，JaCoCo
 * 驱动）：embedding_id 非 Number 且 local_chunk_id 存在时兜底
 * return false（244）。
 */
class PgTrgmFulltextProviderIsExcludedFallbackTest {

    @Test
    void nonNumberEmbeddingIdWithLocalChunkIdReturnsFalse() throws Exception {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var provider = new PgTrgmFulltextProvider(jdbc);
        Method method = PgTrgmFulltextProvider.class
                .getDeclaredMethod("isExcluded", Map.class, List.class);
        method.setAccessible(true);

        var row = new HashMap<String, Object>();
        row.put("embedding_id", "str-id");
        row.put("local_chunk_id", 1);

        var result = method.invoke(provider, row, List.of(1L));
        assertFalse((Boolean) result);
    }

    @Test
    void nullEmbeddingIdWithLocalChunkIdReturnsFalse() throws Exception {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var provider = new PgTrgmFulltextProvider(jdbc);
        Method method = PgTrgmFulltextProvider.class
                .getDeclaredMethod("isExcluded", Map.class, List.class);
        method.setAccessible(true);

        var row = new HashMap<String, Object>();
        row.put("embedding_id", null);
        row.put("local_chunk_id", 1);

        var result = method.invoke(provider, row, List.of(1L));
        assertFalse((Boolean) result);
    }
}
