package com.springairag.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;

/**
 * 构建辅助长尾（Batch 711，JaCoCo 驱动）：RagChatService.
 * buildSortedAdvisors 对非 Ordered 顾问回退最低优先级；
 * JsonRecordService.validatePayloadFilter 委托过滤器校验器。
 */
class BuildHelpersNonOrderedTailTest {

    @Test
    @SuppressWarnings("unchecked")
    void buildSortedAdvisorsDefaultsNonOrderedAdvisorsToLowestPrecedence()
            throws Exception {
        org.springframework.ai.chat.client.ChatClient.Builder builder =
                mock(org.springframework.ai.chat.client.ChatClient.Builder.class);
        org.mockito.Mockito.when(builder.defaultAdvisors(anyList()))
                .thenReturn(builder);
        org.mockito.Mockito.when(builder.build())
                .thenReturn(mock(org.springframework.ai.chat.client.ChatClient.class));
        RagChatService service = new RagChatService(
                builder,
                null,
                mock(QueryRewriteAdvisor.class),
                mock(HybridSearchAdvisor.class),
                mock(RerankAdvisor.class),
                mock(org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository.class),
                mock(com.springairag.core.repository.RagChatHistoryRepository.class),
                mock(com.springairag.core.extension.DomainExtensionRegistry.class),
                mock(com.springairag.core.extension.PromptCustomizerChain.class),
                new com.springairag.core.config.RagProperties(),
                null,
                null,
                null,
                null);

        // 非 Ordered 的自定义顾问 → 回退 LOWEST_PRECEDENCE 后仍参与排序。
        com.springairag.api.service.RagAdvisorProvider provider =
                new com.springairag.api.service.RagAdvisorProvider() {
                    @Override public String getName() { return "plain"; }
                    @Override public int getOrder() { return 0; }
                    @Override public BaseAdvisor createAdvisor() {
                        return mock(BaseAdvisor.class);
                    }
                    @Override public java.util.Set<com.springairag.api.enums.ChatMode>
                    supportedModes() {
                        return java.util.Set.of(com.springairag.api.enums.ChatMode.KNOWLEDGE);
                    }
                    @Override public com.springairag.api.service.AdvisorScope
                    advisorScope() {
                        return com.springairag.api.service.AdvisorScope.ATTEMPT;
                    }
                };

        Method method = RagChatService.class.getDeclaredMethod(
                "buildSortedAdvisors",
                QueryRewriteAdvisor.class,
                HybridSearchAdvisor.class,
                RerankAdvisor.class,
                List.class,
                ChatMemory.class);
        method.setAccessible(true);

        ChatMemory chatMemory = MessageWindowChatMemory.builder().build();
        List<Advisor> sorted = (List<Advisor>) method.invoke(
                service,
                mock(QueryRewriteAdvisor.class),
                mock(HybridSearchAdvisor.class),
                mock(RerankAdvisor.class),
                List.of(provider),
                chatMemory);

        assertEquals(5, sorted.size());
        // 内存顾问固定追加在末尾。
        assertTrue(sorted.get(sorted.size() - 1).getClass().getName()
                .contains("MessageChatMemoryAdvisor"));
    }

    @Test
    void validatePayloadFilterDelegatesToFilterValidator() throws Exception {
        Object service = new com.springairag.core.service.JsonRecordService(
                mock(com.springairag.core.repository.RagDocumentRepository.class),
                mock(com.springairag.core.service.DocumentVersionService.class),
                mock(com.springairag.core.retrieval.HybridRetrieverService.class),
                mock(com.springairag.core.retrieval.ReRankingService.class),
                mock(com.springairag.core.service.CollectionIdentityResolver.class),
                new com.springairag.core.config.RagProperties(),
                new ObjectMapper(),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                null);
        Method method = com.springairag.core.service.JsonRecordService.class
                .getDeclaredMethod("validatePayloadFilter",
                        com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        var mapper = new ObjectMapper();

        Object filter = method.invoke(service,
                mapper.readTree("{\"status\":\"ACTIVE\"}"));
        assertNotNull(filter);

        // 空对象过滤器 → 校验拒绝。
        assertThrows(Exception.class,
                () -> method.invoke(service, mapper.readTree("{}")));
    }
}
