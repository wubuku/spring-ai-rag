package com.springairag.core.config;

import com.springairag.api.dto.ChatRequest;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagChatService 系统提示词与用户消息定制长尾（Batch 581，JaCoCo
 * 驱动）：buildSystemPrompt 无扩展/无模板/定制链替换三分支、
 * customizeUserMessage 无定制直通与定制替换。
 */
class RagChatServiceSystemPromptTailTest {

    private DomainExtensionRegistry domainExtensionRegistry;
    private PromptCustomizerChain promptCustomizerChain;
    private RagChatService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        domainExtensionRegistry = mock(DomainExtensionRegistry.class);
        promptCustomizerChain = mock(PromptCustomizerChain.class);
        var chatClientBuilder = mock(ChatClient.Builder.class,
                org.mockito.Mockito.RETURNS_SELF);
        when(chatClientBuilder.build())
                .thenReturn(mock(org.springframework.ai.chat.client.ChatClient.class));
        service = new RagChatService(
                chatClientBuilder,
                null,
                mock(QueryRewriteAdvisor.class),
                mock(HybridSearchAdvisor.class),
                mock(RerankAdvisor.class),
                mock(JdbcChatMemoryRepository.class),
                mock(RagChatHistoryRepository.class),
                domainExtensionRegistry,
                promptCustomizerChain,
                new RagProperties(),
                null, null, null, null);
    }

    private Object invoke(String name, Class<?>[] params, Object... args)
            throws Exception {
        Method method = RagChatService.class.getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method.invoke(service, args);
    }

    @Test
    void buildSystemPromptReturnsNullWhenNoExtensions() throws Exception {
        when(domainExtensionRegistry.hasExtensions()).thenReturn(false);

        var result = invoke("buildSystemPrompt",
                new Class<?>[]{String.class, Map.class},
                "medical", Map.of());

        assertEquals(null, result);
    }

    @Test
    void buildSystemPromptReturnsNullWhenTemplateMissing() throws Exception {
        when(domainExtensionRegistry.hasExtensions()).thenReturn(true);
        when(domainExtensionRegistry.getSystemPromptTemplate("medical"))
                .thenReturn(null);

        var result = invoke("buildSystemPrompt",
                new Class<?>[]{String.class, Map.class},
                "medical", Map.of());

        assertEquals(null, result);
    }

    @Test
    void buildSystemPromptDelegatesToCustomizerChainWhenPresent()
            throws Exception {
        when(domainExtensionRegistry.hasExtensions()).thenReturn(true);
        when(domainExtensionRegistry.getSystemPromptTemplate("medical"))
                .thenReturn("原始模板");
        when(promptCustomizerChain.hasCustomizers()).thenReturn(true);
        when(promptCustomizerChain.customizeSystemPrompt(
                eq("原始模板"), eq(""), any(Map.class)))
                .thenReturn("定制后模板");

        var result = invoke("buildSystemPrompt",
                new Class<?>[]{String.class, Map.class},
                "medical", Map.of("k", "v"));

        assertEquals("定制后模板", result);
    }

    @Test
    void customizeUserMessagePassesThroughWithoutCustomizers()
            throws Exception {
        when(promptCustomizerChain.hasCustomizers()).thenReturn(false);

        var result = invoke("customizeUserMessage",
                new Class<?>[]{String.class, Map.class},
                "原始消息", Map.of());

        assertEquals("原始消息", result);
    }

    @Test
    void customizeUserMessageAppliesCustomizerWhenPresent() throws Exception {
        when(promptCustomizerChain.hasCustomizers()).thenReturn(true);
        when(promptCustomizerChain.customizeUserMessage(
                eq("原始消息"), eq(Map.of("k", "v"))))
                .thenReturn("定制消息");

        var result = invoke("customizeUserMessage",
                new Class<?>[]{String.class, Map.class},
                "原始消息", Map.of("k", "v"));

        assertEquals("定制消息", result);
    }
}
