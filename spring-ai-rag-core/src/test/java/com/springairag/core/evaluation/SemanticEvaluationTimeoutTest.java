package com.springairag.core.evaluation;

import com.springairag.api.dto.SemanticEvaluationRequest;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** TIMEOUT 分支：求值器超出 answerQualityTimeoutSeconds 预算即降级。 */
class SemanticEvaluationTimeoutTest {

    private SemanticEvaluationRequest request() {
        return new SemanticEvaluationRequest(
                "RELEVANCY", "q", "context-text", "answer-text", "model-a");
    }

    @Test
    void returnsTimeoutStatusWhenTheEvaluatorExceedsItsBudget() throws Exception {
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRetrieval().setAnswerQualityTimeoutSeconds(1);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(
                ChatClient.ChatClientRequestSpec.class,
                withSettings().defaultAnswer(
                        org.mockito.Mockito.RETURNS_SELF));
        when(chatClient.prompt()).thenReturn(spec);
        when(builder.build()).thenReturn(chatClient);

        // Batch 950：原来是 Thread.sleep(2_000) 模拟慢模型调用，2 秒里除了"比
        // 1 秒预算更久"之外没有任何信息量，还会留下一条睡满 2 秒的线程。改成由
        // 测试自己持有的两个 latch：entered 证明链路确实走到了被阻塞的调用，
        // release 让测试在收尾时放掉那条线程。
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(spec.call()).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return null;
        });

        SemanticEvaluationService service =
                new SemanticEvaluationService(builder, ragProperties);

        try {
            var response = service.evaluate(request());

            assertEquals("TIMEOUT", response.status());
            assertNull(response.passed());
            assertNull(response.score());

            // 机制钉，而不是秒表。
            //
            // 原来这里是 `assertTrue(elapsedMs < 2_000, "evaluate should return
            // before the blocked call completes")`——单样本墙钟阈值：机器一忙就
            // 假失败，而它证明的是"跑得够快"，不是"没有等待"。下面两条直接说
            // 那件事本身：调用确实发生过，且 evaluate 返回时它仍被卡着。
            assertTrue(entered.await(3, TimeUnit.SECONDS),
                    "评估链路根本没有走到被阻塞的模型调用，降级原因可能不是超时");
            assertEquals(1L, release.getCount(),
                    "evaluate 返回时阻塞调用仍被卡住——说明它确实没有等它做完");
        } finally {
            release.countDown();
        }
    }
}