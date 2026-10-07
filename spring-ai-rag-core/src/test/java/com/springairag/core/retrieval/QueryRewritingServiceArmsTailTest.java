package com.springairag.core.retrieval;

import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 查询改写第二段长尾（Batch 965，JaCoCo 驱动）：init 对"配置对象存在
 * 但词表为 null"的空表降级、重试模板 lambda 体的成功路径、以及重试
 * 耗尽异常的 cause 包装与受检异常分支。
 */
class QueryRewritingServiceArmsTailTest {

    private void injectRetryTemplate(
            QueryRewritingService service,
            RetryTemplate retryTemplate) throws Exception {
        Field field = QueryRewritingService.class
                .getDeclaredField("retryTemplate");
        field.setAccessible(true);
        field.set(service, retryTemplate);
    }

    private ChatModel chatModelReturning(String content) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(content)))));
        return model;
    }

    @Test
    void initDefaultsEmptySynonymAndQualifierWhenConfigListsMissing() {
        // 新 RagProperties 的 queryRewrite 已自动创建，但词表/限定词为
        // null → init 落到空表降级，不改写任何内容。
        QueryRewritingService service = new QueryRewritingService(
                new RagProperties());
        service.init();

        assertEquals(List.of("原始查询"),
                service.rewriteQuery("原始查询"));
    }

    @Test
    void initToleratesExplicitlyNulledListsFromRuntimeOverride() {
        // 字段默认值是 emptyMap/emptyList，但 setter 允许注入 null
        // （运行时覆盖的合法入口）→ init 的 null 回退必须兜住。
        RagProperties properties = new RagProperties();
        properties.getQueryRewrite().setSynonymDictionary(null);
        properties.getQueryRewrite().setDomainQualifiers(null);
        QueryRewritingService service = new QueryRewritingService(
                properties);
        service.init();

        assertEquals(List.of("原始查询"),
                service.rewriteQuery("原始查询"));
    }

    @Test
    void mockedRetryTemplateExecutesLambdaBodySuccessfully()
            throws Exception {
        QueryRewritingService service = new QueryRewritingService(
                new RagProperties());
        service.init();
        RetryTemplate template = mock(RetryTemplate.class);
        RetryContext firstAttempt = mock(RetryContext.class);
        when(firstAttempt.getRetryCount()).thenReturn(0);
        when(template.execute(any()))
                .thenAnswer(invocation -> ((RetryCallback<String, Exception>)
                        invocation.getArgument(0))
                        .doWithRetry(firstAttempt));
        injectRetryTemplate(service, template);

        List<String> rewritten = service.llmRewrite(
                "查询", chatModelReturning("改写一\n改写二"));

        assertTrue(rewritten.contains("改写一"));
        assertTrue(rewritten.contains("改写二"));
    }

    @Test
    void retryExhaustionWithCauseDegradesToEmpty() throws Exception {
        QueryRewritingService service = new QueryRewritingService(
                new RagProperties());
        service.init();
        RetryTemplate template = mock(RetryTemplate.class);
        // 带 cause 的异常 → 走 getCause()!=null 的取因分支。
        when(template.execute(any())).thenAnswer(invocation -> {
            throw new IllegalStateException("wrapper",
                    new IllegalArgumentException("root"));
        });
        injectRetryTemplate(service, template);

        assertTrue(service.llmRewrite("查询", chatModelReturning("x"))
                .isEmpty());
    }

    @Test
    void retryExhaustionWithCheckedExceptionWrapsInRuntime() throws Exception {
        QueryRewritingService service = new QueryRewritingService(
                new RagProperties());
        service.init();
        RetryTemplate template = mock(RetryTemplate.class);
        // 受检异常（非 RuntimeException）→ 走 new RuntimeException(cause)
        // 的包装分支，llmRewrite 照样降级为空。
        when(template.execute(any())).thenAnswer(invocation -> {
            throw new java.text.ParseException("bad", 0);
        });
        injectRetryTemplate(service, template);

        assertTrue(service.llmRewrite("查询", chatModelReturning("x"))
                .isEmpty());
    }
}
