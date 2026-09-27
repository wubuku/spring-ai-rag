package com.springairag.core.openai;

import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.config.RagProperties;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpenAiRequestRetrievalScopeAdapter IAE 映射与 null 请求长尾
 * （Batch 676，JaCoCo 驱动）：resolver 抛 IllegalArgumentException
 * 时映射为 invalid_scope 协议异常、null request 时 headerKeys 为
 * 空列表。
 */
class OpenAiRequestRetrievalScopeAdapterIaeNullTailTest {

    private CollectionRetrievalScopeResolver resolver;
    private RagProperties properties;
    private OpenAiRequestRetrievalScopeAdapter adapter;

    @BeforeEach
    void setUp() {
        resolver = mock(CollectionRetrievalScopeResolver.class);
        properties = new RagProperties();
        adapter = new OpenAiRequestRetrievalScopeAdapter(resolver, properties);
    }

    @Test
    void resolverIllegalArgumentBecomesInvalidScopeError() {
        when(resolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("无效的集合键"));

        var error = assertThrows(OpenAiProtocolException.class,
                () -> adapter.resolve(
                        new OpenAiChatCompletionRequest.RagOptions(),
                        new MockHttpServletRequest()));

        assertTrue(error.getMessage().contains("无效的集合键"));
    }

    @Test
    void nullRequestHeaderKeysReturnsEmptyList() throws Exception {
        var method = OpenAiRequestRetrievalScopeAdapter.class
                .getDeclaredMethod("headerKeys",
                        jakarta.servlet.http.HttpServletRequest.class);
        method.setAccessible(true);

        var result = method.invoke(adapter, (Object) null);

        assertEquals(List.of(), result);
    }

    @Test
    void requestWithoutHeaderKeysReturnsEmptyList() throws Exception {
        var method = OpenAiRequestRetrievalScopeAdapter.class
                .getDeclaredMethod("headerKeys",
                        jakarta.servlet.http.HttpServletRequest.class);
        method.setAccessible(true);
        var request = new MockHttpServletRequest();

        var result = method.invoke(adapter, request);

        assertEquals(List.of(), result);
    }
}
