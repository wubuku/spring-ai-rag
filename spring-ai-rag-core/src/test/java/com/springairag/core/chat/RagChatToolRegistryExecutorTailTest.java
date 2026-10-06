package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolContextKeys;
import com.springairag.api.service.RagChatToolPolicy;
import com.springairag.api.service.RagChatToolProvider;
import com.springairag.api.service.RagChatToolRequestContext;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.rag.KnowledgeSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagChatToolRegistry 执行器与包装长尾（Batch 654，JaCoCo 驱
 * 动）：启用 JsonRecordSearchTool 时的结构化记录 provider 注册、
 * 执行器饱和错误、结果超长截断为 tool_result_too_large、请求死线
 * 已过与 future 超时的 tool_timeout、包装回调透传元数据。
 */
class RagChatToolRegistryExecutorTailTest {

    private ToolCallback blockingCallback(String name,
                                          CountDownLatch latch) {
        return blockingCallback(name, latch, new CountDownLatch(0));
    }

    /**
     * @param started 在回调体**真正开始执行**时倒数，用来证明"这个调用已经占住了
     *                执行线程"，而不是靠睡一会儿去希望它已经跑起来了。
     */
    private ToolCallback blockingCallback(String name,
                                          CountDownLatch latch,
                                          CountDownLatch started) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(definition.inputSchema()).thenReturn("{}");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.getToolMetadata()).thenReturn(
                ToolMetadata.builder().returnDirect(false).build());
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(invocation -> {
                    started.countDown();
                    latch.await(3, TimeUnit.SECONDS);
                    return "ok";
                });
        return callback;
    }

    private RagChatToolPolicy policy(int maxResultCharacters,
                                     Duration timeout) {
        return new RagChatToolPolicy(
                RagChatToolPolicy.Effect.READ_ONLY,
                3,
                maxResultCharacters,
                timeout);
    }

    private RagChatToolRegistry registry(
            RagChatProperties properties,
            RagChatToolPolicy policy,
            List<ToolCallback> callbacks,
            com.springairag.core.rag.JsonRecordSearchTool jsonRecordSearchTool) {
        RagChatToolProvider provider = new RagChatToolProvider() {
            @Override
            public String getName() {
                return "external";
            }

            @Override
            public Set<String> supportedDomains() {
                return Set.of();
            }

            @Override
            public Map<String, RagChatToolPolicy> getToolPolicies() {
                return callbacks.stream().collect(java.util.stream.Collectors
                        .toMap(callback ->
                                        callback.getToolDefinition().name(),
                                ignored -> policy));
            }

            @Override
            public List<ToolCallback> getToolCallbacks() {
                return callbacks;
            }
        };
        KnowledgeSearchTool knowledge = mock(KnowledgeSearchTool.class);
        when(knowledge.getToolDefinition()).thenReturn(
                ToolDefinition.builder()
                        .name("searchKnowledge")
                        .description("search")
                        .inputSchema("{}")
                        .build());
        when(knowledge.getToolMetadata()).thenReturn(
                ToolMetadata.builder().returnDirect(false).build());
        return new RagChatToolRegistry(
                properties,
                knowledge,
                jsonRecordSearchTool,
                List.of(provider));
    }

    private ToolCallback registeredTool(RagChatToolRegistry registry,
                                        String name) {
        return registry.callbacks(ChatMode.AGENT, null).stream()
                .filter(tool -> name.equals(
                        tool.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
    }

    private ToolContext context(Instant deadline) {
        return new ToolContext(Map.of(
                RagChatToolContextKeys.REQUEST,
                new RagChatToolRequestContext(
                        "principal", "USER", false, "session",
                        null, ChatMode.AGENT, "test/model", deadline),
                ChatExecutionBudget.CONTEXT_KEY,
                new ChatExecutionBudget(
                        Instant.now().plusSeconds(30), 8, 8, 3, 8, 2,
                        4_000)));
    }

    @Test
    void enabledJsonRecordSearchToolRegistersStructuredRecordProvider() {
        RagChatProperties properties = new RagChatProperties();
        var jsonRecordSearchTool =
                mock(com.springairag.core.rag.JsonRecordSearchTool.class);
        when(jsonRecordSearchTool.isEnabled()).thenReturn(true);
        when(jsonRecordSearchTool.getToolDefinition()).thenReturn(
                ToolDefinition.builder()
                        .name("searchStructuredRecord")
                        .description("structured")
                        .inputSchema("{}")
                        .build());
        when(jsonRecordSearchTool.getToolMetadata()).thenReturn(
                ToolMetadata.builder().returnDirect(false).build());

        RagChatToolRegistry registry = registry(
                properties,
                policy(1_024, Duration.ofSeconds(1)),
                List.of(),
                jsonRecordSearchTool);

        assertNotNull(registry.callbacks(ChatMode.AGENT, null).stream()
                .filter(tool -> "searchStructuredRecord".equals(
                        tool.getToolDefinition().name()))
                .findFirst()
                .orElseThrow());
        // 包装回调透传委托元数据。
        assertEquals(false, registeredTool(registry, "searchStructuredRecord")
                .getToolMetadata().returnDirect());
    }

    private static void assertNotNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNotNull(value);
    }

    @Test
    void oversizedToolResultBecomesTooLargeError() {
        RagChatProperties properties = new RagChatProperties();
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("lookupInventory");
        when(definition.inputSchema()).thenReturn("{}");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.getToolMetadata()).thenReturn(
                ToolMetadata.builder().returnDirect(false).build());
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenReturn("x".repeat(2_000));

        RagChatToolRegistry registry = registry(
                properties, policy(1_024, Duration.ofSeconds(2)),
                List.of(callback), null);

        String result = registeredTool(registry, "lookupInventory")
                .call("{}", context(Instant.now().plusSeconds(10)));

        assertEquals("{\"error\":\"tool_result_too_large\"}", result);
    }

    @Test
    void expiredRequestDeadlineShortCircuitsWithTimeoutError() {
        RagChatProperties properties = new RagChatProperties();
        CountDownLatch latch = new CountDownLatch(1);
        ToolCallback callback = blockingCallback("lookupInventory", latch);
        RagChatToolRegistry registry = registry(
                properties, policy(1_024, Duration.ofSeconds(5)),
                List.of(callback), null);

        String result = registeredTool(registry, "lookupInventory")
                .call("{}", context(Instant.now().minusSeconds(5)));
        latch.countDown();

        assertEquals("{\"error\":\"tool_timeout\"}", result);
    }

    @Test
    void slowToolTriggersFutureTimeoutError() {
        RagChatProperties properties = new RagChatProperties();
        CountDownLatch latch = new CountDownLatch(1);
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("lookupInventory");
        when(definition.inputSchema()).thenReturn("{}");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.getToolMetadata()).thenReturn(
                ToolMetadata.builder().returnDirect(false).build());
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(invocation -> {
                    latch.await(3, TimeUnit.SECONDS);
                    return "ok";
                });
        RagChatToolRegistry registry = registry(
                properties, policy(1_024, Duration.ofMillis(1)),
                List.of(callback), null);

        String result = registeredTool(registry, "lookupInventory")
                .call("{}", context(Instant.now().plusSeconds(30)));
        latch.countDown();

        assertEquals("{\"error\":\"tool_timeout\"}", result);
    }

    @Test
    void saturatedExecutorReturnsSaturatedError() throws Exception {
        RagChatProperties properties = new RagChatProperties();
        properties.getAgent().setToolExecutorThreads(1);
        properties.getAgent().setToolExecutorQueueCapacity(1);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        ToolCallback callback = blockingCallback("lookupInventory", latch, firstStarted);
        RagChatToolRegistry registry = registry(
                properties, policy(1_024, Duration.ofSeconds(5)),
                List.of(callback), null);
        ToolCallback registered = registeredTool(registry, "lookupInventory");

        // 占满唯一线程与容量 1 的队列后，第三个调用被拒绝。
        Thread first = new Thread(() -> registered.call(
                "{}", context(Instant.now().plusSeconds(30))));
        Thread second = new Thread(() -> registered.call(
                "{}", context(Instant.now().plusSeconds(30))));
        first.start();
        // 原来是 Thread.sleep(150) 去"希望"第一个调用已经跑起来了。等回调体真正开始
        // 执行才有这个保证——睡够 150ms 并不保证调度发生过。
        assertTrue(firstStarted.await(3, TimeUnit.SECONDS),
                "第一个调用没有拿到执行线程，饱和场景根本没被建立起来");
        second.start();
        // 这一段**仍然是睡**：它在等第二个调用被**提交进**执行器（队列容量 1）。
        // 测试拿不到执行器本身，看不到队列，所以没有可等待的信号——这是已知的
        // 150ms 窗口，不是因为"这里需要时间"。
        Thread.sleep(150);
        String saturated = registered.call(
                "{}", context(Instant.now().plusSeconds(30)));
        latch.countDown();
        first.join(3_000);
        second.join(3_000);

        assertEquals("{\"error\":\"tool_executor_saturated\"}", saturated);
    }
}
