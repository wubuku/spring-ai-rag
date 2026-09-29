package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.entity.RagCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CollectionPurgeService 私有助手长尾（Batch 720，JaCoCo 驱动）：
 * 版本/聊天围栏对 null 版本归零、countUuidJoin 空集合短路、
 * purge 计划序列化失败包装与正常序列化。
 */
class CollectionPurgeServiceHelperTailTest {

    private JdbcTemplate jdbcTemplate;
    private CollectionPurgeService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new CollectionPurgeService(
                jdbcTemplate,
                new ObjectMapper(),
                mock(com.springairag.core.repository.RagCollectionRepository.class),
                mock(com.springairag.core.service.CollectionPurgeAuthorization.class),
                new com.springairag.core.config.RagProperties(),
                mock(PlatformTransactionManager.class));
    }

    private Object invoke(String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = CollectionPurgeService.class
                .getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(service, args);
    }

    @Test
    void versionHelpersDefaultNullVersionsToZero() throws Exception {
        RagCollection collection = new RagCollection();

        assertEquals(0L, invoke("version", new Class<?>[]{RagCollection.class}, collection));
        assertEquals(0L, invoke("chatFence", new Class<?>[]{RagCollection.class}, collection));

        collection.setVersion(5L);
        collection.setChatCommitFenceVersion(7L);
        assertEquals(5L, invoke("version", new Class<?>[]{RagCollection.class}, collection));
        assertEquals(7L, invoke("chatFence", new Class<?>[]{RagCollection.class}, collection));
    }

    @Test
    void countUuidJoinShortCircuitsEmptyIds() throws Exception {
        assertEquals(0L, invoke("countUuidJoin",
                new Class<?>[]{String.class, String.class, List.class},
                "t", "c", List.of()));
        verify(jdbcTemplate, never()).queryForObject(
                org.mockito.ArgumentMatchers.contains("t"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void countUuidJoinCountsPlaceholderQuery() throws Exception {
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.contains("IN ("),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.any(UUID.class)))
                .thenReturn(3L);

        assertEquals(3L, invoke("countUuidJoin",
                new Class<?>[]{String.class, String.class, List.class},
                "rag_docs", "id", List.of(UUID.randomUUID())));
    }

    @Test
    void jsonWrapsSerializationFailure() throws Exception {
        var error = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> invoke("json", new Class<?>[]{Object.class}, new Object()));
        var cause = (IllegalStateException) error.getCause();
        assertEquals("Unable to serialize purge plan", cause.getMessage());
    }

    @Test
    void jsonSerializesSimplePlans() throws Exception {
        String json = (String) invoke("json", new Class<?>[]{Object.class},
                Map.of("k", "v"));
        assertEquals("{\"k\":\"v\"}", json);
    }
}
