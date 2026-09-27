package com.springairag.core.retrieval.fulltext;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PgTrgmFulltextProvider isExcluded 排除臂长尾（Batch 682，JaCoCo
 * 驱动）：embedding_id 为 Number 时匹配排除列表、id 兼容路径、
 * 无排除列表返回 false、非排除 ID 返回 false。
 */
class PgTrgmFulltextProviderIsExcludedTailTest {

    private boolean isExcluded(Object provider, Map<String, Object> row,
                               List<Long> excludeIds) throws Exception {
        Method method = PgTrgmFulltextProvider.class
                .getDeclaredMethod("isExcluded", Map.class, List.class);
        method.setAccessible(true);
        return (boolean) method.invoke(provider, row, excludeIds);
    }

    private Object newProvider() {
        return new PgTrgmFulltextProvider(
                mock(org.springframework.jdbc.core.JdbcTemplate.class));
    }

    @Test
    void nullExcludeIdsReturnsFalse() throws Exception {
        var row = Map.<String, Object>of("embedding_id", 1);
        assertFalse(isExcluded(newProvider(), row, null));
    }

    @Test
    void emptyExcludeIdsReturnsFalse() throws Exception {
        var row = Map.<String, Object>of("embedding_id", 1);
        assertFalse(isExcluded(newProvider(), row, List.of()));
    }

    @Test
    void embeddingIdInExcludeListReturnsTrue() throws Exception {
        var row = Map.<String, Object>of("embedding_id", 5);
        assertTrue(isExcluded(newProvider(), row, List.of(5L)));
    }

    @Test
    void embeddingIdNotInExcludeListReturnsFalse() throws Exception {
        var row = Map.<String, Object>of("embedding_id", 5);
        assertFalse(isExcluded(newProvider(), row, List.of(99L)));
    }

    @Test
    void stringEmbeddingIdIsSkippedButIdCompatFires() throws Exception {
        // embedding_id 为 String → instanceof Number 为 false →
        // 走 local_chunk_id/id 兼容路径。
        var row = new java.util.HashMap<String, Object>();
        row.put("embedding_id", "not-a-number");
        row.put("id", 7L);
        assertTrue(isExcluded(newProvider(), row, List.of(7L)));
    }

    @Test
    void stringEmbeddingIdNotMatchingReturnsFalse() throws Exception {
        var row = new java.util.HashMap<String, Object>();
        row.put("embedding_id", "not-a-number");
        row.put("id", 99L);
        assertFalse(isExcluded(newProvider(), row, List.of(5L)));
    }
}
