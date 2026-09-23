package com.springairag.core.retrieval;

import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 查询改写长尾（Batch 595，JaCoCo 驱动）：init 对空配置与空词表
 * 的降级、运行时覆盖 null 词表、rewriteQuery 守卫、同义词空数组
 * 跳过、LLM 改写的执行模型覆盖/空响应/重试耗尽降级、padding 查询
 * 的前缀与后缀去重及短词过滤。
 */
class QueryRewritingServiceTailTest {

    @Test
    void noArgConstructorDegradesToDefaultConfig() {
        QueryRewritingService service = new QueryRewritingService();
        service.init();

        // 默认配置未注入同义词/限定词 → 仅返回原始查询。
        assertEquals(List.of("原始查询"), service.rewriteQuery("原始查询"));
        assertTrue(!service.generatePaddingQueries("原始查询").isEmpty());
    }

    @Test
    void initPicksUpConfiguredSynonymsAndQualifiers() {
        RagProperties properties = new RagProperties();
        properties.getQueryRewrite().setEnabled(true);
        properties.getQueryRewrite().setSynonymDictionary(
                Map.of("退货", new String[] {"退款"}));
        properties.getQueryRewrite().setDomainQualifiers(List.of("官方"));

        QueryRewritingService service = new QueryRewritingService(properties);
        service.init();

        List<String> rewritten = service.rewriteQuery("如何退货");
        assertTrue(rewritten.stream().anyMatch(q -> q.contains("退款")));
        assertTrue(rewritten.stream().anyMatch(q -> q.contains("官方")));
    }

    @Test
    void runtimeOverridesTolerateNullInputs() {
        QueryRewritingService service = new QueryRewritingService(new RagProperties());
        service.setSynonymDictionary(null);
        service.setDomainQualifiers(null);

        assertEquals(List.of("查询"), service.rewriteQuery("查询"));
    }

    @Test
    void rewriteQueryTreatsBlankQueryAsNoOp() {
        QueryRewritingService service = new QueryRewritingService(
                new RagProperties());
        service.setSynonymDictionary(Map.of("退货", new String[] {"退款"}));

        // 空白查询直接短路返回（仍含空白原串）。
        List<String> result = service.rewriteQuery("   ");
        assertEquals(1, result.size());
    }

    @Test
    void expandWithSynonymsSkipsNullAndEmptySynonymArrays() {
        QueryRewritingService service = new QueryRewritingService(
                new RagProperties());
        service.init();
        Map<String, String[]> dictionary = new HashMap<>();
        dictionary.put("退货", new String[0]);
        dictionary.put("退款", null);
        service.setSynonymDictionary(dictionary);

        assertEquals(List.of("如何退款"), service.rewriteQuery("如何退款"));
    }

    @Test
    void llmRewriteWithoutAnyModelReturnsEmpty() {
        QueryRewritingService service = new QueryRewritingService(
                new RagProperties());
        service.init();

        assertEquals(List.of(), service.llmRewrite("查询"));
    }

    @Test
    void llmRewriteParsesLinesThroughExecutionModelOverride() {
        RagProperties properties = new RagProperties();
        properties.getQueryRewrite().setLlmMaxRewrites(3);
        QueryRewritingService service = new QueryRewritingService(properties);
        service.init();
        ChatModel override = mock(ChatModel.class);
        when(override.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(
                        "1. 改写一\n- 改写二\n原查询"))),
                ChatResponseMetadata.builder().build()));

        List<String> rewritten = service.llmRewrite("原查询", override);

        // 编号与项目符号被清洗，与原查询相同的行被过滤。
        assertEquals(List.of("改写一", "改写二"), rewritten);
    }

    @Test
    void llmRewriteDegradesToEmptyOnEmptyModelResponse() {
        QueryRewritingService service = new QueryRewritingService(
                new RagProperties());
        service.init();
        ChatModel override = mock(ChatModel.class);
        when(override.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(), ChatResponseMetadata.builder().build()));

        assertEquals(List.of(), service.llmRewrite("查询", override));
    }

    @Test
    void retryTemplateRetriesThenGivesUpGracefully() throws Exception {
        QueryRewritingService service = new QueryRewritingService(
                new RagProperties());
        service.init();
        ChatModel flaky = mock(ChatModel.class);
        when(flaky.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("boom-1"))
                .thenThrow(new IllegalStateException("boom-2"));
        RetryTemplate retryTemplate = RetryTemplate.builder()
                .maxAttempts(2)
                .retryOn(IllegalStateException.class)
                .build();
        Field field = QueryRewritingService.class
                .getDeclaredField("retryTemplate");
        field.setAccessible(true);
        field.set(service, retryTemplate);

        // 重试耗尽后静默降级为空结果。
        assertEquals(List.of(), service.llmRewrite("查询", flaky));
    }

    @Test
    void generatePaddingQueriesSkipsDuplicatedPrefixesAndSuffixes() {
        RagProperties properties = new RagProperties();
        properties.getQueryRewrite().setEnabled(true);
        properties.getQueryRewrite().setPaddingCount(50);
        QueryRewritingService service = new QueryRewritingService(properties);
        service.init();

        List<String> padding = service.generatePaddingQueries("如何退货怎么办");

        // 查询已以 "如何" 开头并含 "怎么办" → 这两条变体不应重复生成。
        assertTrue(padding.stream().noneMatch(q -> q.equals("如何" + "如何退货怎么办")));
        assertTrue(padding.stream().noneMatch(q -> q.equals("如何退货怎么办" + "怎么办")));
        assertTrue(padding.size() > 2);
    }

    @Test
    void generatePaddingQueriesIgnoresShortFragments() {
        RagProperties properties = new RagProperties();
        properties.getQueryRewrite().setEnabled(true);
        properties.getQueryRewrite().setPaddingCount(50);
        QueryRewritingService service = new QueryRewritingService(properties);
        service.init();

        // "ab" 长度 >= 2 保留，"x" 长度不足被丢弃 → 不产生组合。
        List<String> padding = service.generatePaddingQueries("ab, x");
        assertTrue(padding.stream().noneMatch(q -> q.contains("x和")));
    }

    @Test
    void generatePaddingQueriesReturnsEmptyForBlankOrDisabled() {
        RagProperties properties = new RagProperties();
        properties.getQueryRewrite().setEnabled(false);
        QueryRewritingService disabled = new QueryRewritingService(properties);
        assertTrue(disabled.generatePaddingQueries("查询").isEmpty());

        RagProperties enabledProperties = new RagProperties();
        enabledProperties.getQueryRewrite().setEnabled(true);
        QueryRewritingService service =
                new QueryRewritingService(enabledProperties);
        service.init();
        assertTrue(service.generatePaddingQueries("  ").isEmpty());
        assertTrue(service.generatePaddingQueries(null).isEmpty());
    }
}
