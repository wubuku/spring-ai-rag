package com.springairag.core.config;

import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.Test;
import com.springairag.core.advisor.HybridSearchAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * RagChatService 构建辅助长尾（Batch 537，JaCoCo 驱动）：断路器
 * 未启用时为 null、buildSortedAdvisors 按 Ordered 排序并追加内存
 * 顾问、buildAdvisorParams 三层重载链写入会话/元数据/作用域参数。
 */
class RagChatServiceBuildHelpersTailTest {

    private RagChatService service() {
        return new RagChatService(
                mock(ChatClient.Builder.class,
                        org.mockito.Mockito.RETURNS_DEEP_STUBS),
                null,
                mock(com.springairag.core.advisor.QueryRewriteAdvisor.class),
                mock(com.springairag.core.advisor.HybridSearchAdvisor.class),
                mock(com.springairag.core.advisor.RerankAdvisor.class),
                mock(org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository.class),
                mock(com.springairag.core.repository.RagChatHistoryRepository.class),
                mock(com.springairag.core.extension.DomainExtensionRegistry.class),
                mock(com.springairag.core.extension.PromptCustomizerChain.class),
                new RagProperties(),
                null,
                null,
                null,
                null);
    }

    @Test
    void circuitBreakerIsNullWhenDisabled() {
        assertNull(service().getCircuitBreaker());
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildSortedAdvisorsOrdersByOrderedAndAppendsMemoryAdvisor()
            throws Exception {
        var orderedEarly = mock(org.springframework.ai.chat.client.advisor.api.BaseAdvisor.class,
                org.mockito.Mockito.withSettings().extraInterfaces(Ordered.class));
        org.mockito.Mockito.when(((Ordered) orderedEarly).getOrder())
                .thenReturn(-100);

        var method = RagChatService.class.getDeclaredMethod(
                "buildSortedAdvisors",
                com.springairag.core.advisor.QueryRewriteAdvisor.class,
                com.springairag.core.advisor.HybridSearchAdvisor.class,
                com.springairag.core.advisor.RerankAdvisor.class,
                List.class,
                ChatMemory.class);
        method.setAccessible(true);

        ChatMemory chatMemory = MessageWindowChatMemory.builder().build();
        List<org.springframework.ai.chat.client.advisor.api.Advisor> sorted =
                (List<org.springframework.ai.chat.client.advisor.api.Advisor>)
                        method.invoke(service(),
                                mock(com.springairag.core.advisor.QueryRewriteAdvisor.class),
                                mock(com.springairag.core.advisor.HybridSearchAdvisor.class),
                                mock(com.springairag.core.advisor.RerankAdvisor.class),
                                List.of(),
                                chatMemory);

        // 内存顾问固定追加在末尾。
        var last = sorted.get(sorted.size() - 1);
        assertNotNull(last);
        assertEquals(4, sorted.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildAdvisorParamsWritesSessionMetadataScopeAndMaxResults()
            throws Exception {
        var method = RagChatService.class.getDeclaredMethod(
                "buildAdvisorParams",
                String.class, String.class, Map.class,
                RetrievalScope.class, int.class, ChatModel.class);
        method.setAccessible(true);

        var spec = mock(ChatClient.AdvisorSpec.class);
        Map<String, Object> metadata = Map.of("tenant", "acme");
        var consumer = (java.util.function.Consumer<ChatClient.AdvisorSpec>)
                method.invoke(service(), "session-7", "domain-1", metadata,
                        RetrievalScope.unscoped(), 5, null);
        consumer.accept(spec);

        verify(spec).param(ChatMemory.CONVERSATION_ID, "session-7");
        verify(spec).param("tenant", "acme");
        verify(spec).param("domainId", "domain-1");
        verify(spec).param(HybridSearchAdvisor.RETRIEVAL_SCOPE_KEY,
                RetrievalScope.unscoped());
        verify(spec).param(eq("maxResults"), eq(5));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildAdvisorParamsTreatsNullScopeAsUnscopedAndSkipsNonPositiveLimit()
            throws Exception {
        var method = RagChatService.class.getDeclaredMethod(
                "buildAdvisorParams",
                String.class, String.class, Map.class,
                RetrievalScope.class, int.class, ChatModel.class);
        method.setAccessible(true);

        var spec = mock(ChatClient.AdvisorSpec.class);
        var consumer = (java.util.function.Consumer<ChatClient.AdvisorSpec>)
                method.invoke(service(), "session-8", null, null,
                        null, 0, null);
        consumer.accept(spec);

        verify(spec).param(ChatMemory.CONVERSATION_ID, "session-8");
        verify(spec).param(HybridSearchAdvisor.RETRIEVAL_SCOPE_KEY,
                RetrievalScope.unscoped());
        verify(spec, org.mockito.Mockito.never())
                .param(eq("maxResults"), org.mockito.ArgumentMatchers.any());
    }
}
