package com.springairag.core.service;

import com.springairag.core.retrieval.RetrievalOutcome;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JsonRecordService 内部助手长尾（Batch 620，JaCoCo 驱动）：
 * requestCollectionKey 对 null 请求返回 null；safeError 对空消息
 * 回退异常类名；DetailedSearchResult 对 null traceResults 收敛为
 * 空列表。
 */
class JsonRecordServiceInternalTailTest {

    private final JsonRecordService service = new JsonRecordService(
            null, null, null, null, null, null, null,
            new com.springairag.core.config.RagProperties(),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            null, null, null);

    @Test
    void requestCollectionKeyReturnsNullForNullRequest() throws Exception {
        Method method = JsonRecordService.class.getDeclaredMethod(
                "requestCollectionKey",
                com.springairag.api.dto.JsonRecordUpsertRequest.class);
        method.setAccessible(true);

        assertNull(method.invoke(service, (Object) null));
    }

    @Test
    void safeErrorFallsBackToExceptionSimpleNameWhenMessageBlank()
            throws Exception {
        Method method = JsonRecordService.class.getDeclaredMethod(
                "safeError", RuntimeException.class);
        method.setAccessible(true);

        String blankMessage = (String) method.invoke(service,
                new RuntimeException("  "));
        String nullMessage = (String) method.invoke(service,
                new RuntimeException((String) null));

        assertEquals("RuntimeException", blankMessage);
        assertEquals("RuntimeException", nullMessage);
        assertTrue(method.invoke(service,
                new RuntimeException("real error")).toString()
                .contains("real error"));
    }

    @Test
    void detailedSearchResultNormalizesNullTraceResults() throws Exception {
        Constructor<JsonRecordService.DetailedSearchResult> ctor =
                JsonRecordService.DetailedSearchResult.class
                        .getDeclaredConstructor(
                                com.springairag.api.dto.JsonRecordSearchResponse.class,
                                RetrievalOutcome.class, List.class);
        ctor.setAccessible(true);

        JsonRecordService.DetailedSearchResult result = ctor.newInstance(
                mock(com.springairag.api.dto.JsonRecordSearchResponse.class),
                mock(RetrievalOutcome.class), null);

        assertEquals(List.of(), result.traceResults());
    }
}
