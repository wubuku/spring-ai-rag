package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolContextKeys;
import com.springairag.api.service.RagChatToolPolicy;
import com.springairag.api.service.RagChatToolProvider;
import com.springairag.api.service.RagChatToolRequestContext;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.rag.JsonRecordSearchTool;
import com.springairag.core.rag.KnowledgeSearchTool;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Immutable startup-validated registry for server-owned AGENT tools.
 */
@Component
public final class RagChatToolRegistry {

    private final RagChatProperties properties;
    private final ThreadPoolExecutor executor;
    private final List<RegisteredTool> tools;

    public RagChatToolRegistry(
            RagChatProperties properties,
            KnowledgeSearchTool knowledgeSearchTool,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            JsonRecordSearchTool jsonRecordSearchTool,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            List<RagChatToolProvider> externalProviders) {
        this.properties = properties;
        RagChatProperties.AgentProperties agent = properties.getAgent();
        this.executor = new ThreadPoolExecutor(
                Math.max(1, agent.getToolExecutorThreads()),
                Math.max(1, agent.getToolExecutorThreads()),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(
                        Math.max(1, agent.getToolExecutorQueueCapacity())),
                new ThreadPoolExecutor.AbortPolicy());
        List<ProviderEntry> providers = new ArrayList<>();
        providers.add(new ProviderEntry(
                new BuiltinProvider(
                        "builtin-knowledge",
                        List.of(knowledgeSearchTool)),
                0));
        if (jsonRecordSearchTool != null && jsonRecordSearchTool.isEnabled()) {
            providers.add(new ProviderEntry(
                    new BuiltinProvider(
                            "builtin-structured-record",
                            List.of(jsonRecordSearchTool)),
                    1));
        }
        if (externalProviders != null) {
            externalProviders.stream()
                    .map(provider -> new ProviderEntry(provider, provider.getOrder()))
                    .forEach(providers::add);
        }
        this.tools = validateAndFreeze(providers);
    }

    public List<ToolCallback> callbacks(ChatMode mode, String domainId) {
        return tools.stream()
                .filter(tool -> supports(tool.provider(), mode, domainId))
                .sorted(Comparator
                        .comparingInt((RegisteredTool tool) -> tool.provider().getOrder())
                        .thenComparing(tool -> tool.provider().getName())
                        .thenComparing(tool -> tool.callback().getToolDefinition().name()))
                .map(RegisteredTool::callback)
                .toList();
    }

    public Map<String, Object> requestContext(ChatCommand command,
                                               ChatModelRouter.ChatModelCandidate candidate) {
        RagChatToolRequestContext request = new RagChatToolRequestContext(
                command.principal().id(),
                command.principal().type(),
                command.principal().admin(),
                command.sessionId(),
                command.domainId(),
                command.mode(),
                candidate.ref(),
                command.executionBudget() != null
                        ? command.executionBudget().deadline()
                        : null);
        Map<String, Object> context = new HashMap<>();
        context.put(RagChatToolContextKeys.REQUEST, request);
        if (command.executionBudget() != null) {
            context.put(ChatExecutionBudget.CONTEXT_KEY,
                    command.executionBudget());
        }
        Map<String, Integer> limits = tools.stream()
                .filter(tool -> supports(tool.provider(), command.mode(),
                        command.domainId()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        tool -> tool.callback().getToolDefinition().name(),
                        tool -> tool.policy().maxResultCharacters()));
        context.put(
                ChatExecutionBudget.TOOL_RESULT_CHARACTER_LIMITS_CONTEXT_KEY,
                limits);
        return Map.copyOf(context);
    }

    private List<RegisteredTool> validateAndFreeze(List<ProviderEntry> providers) {
        Set<String> names = new HashSet<>();
        List<RegisteredTool> result = new ArrayList<>();
        for (ProviderEntry entry : providers) {
            RagChatToolProvider provider = entry.provider();
            if (provider == null || provider.getName() == null
                    || provider.getName().isBlank()) {
                throw new IllegalStateException("RagChatToolProvider name must not be blank");
            }
            if (provider.supportedModes() == null
                    || provider.supportedDomains() == null
                    || provider.getToolCallbacks() == null) {
                throw new IllegalStateException(
                        "RagChatToolProvider '" + provider.getName()
                                + "' returned null registration data");
            }
            Map<String, RagChatToolPolicy> policies = provider.getToolPolicies();
            if (policies == null) {
                throw new IllegalStateException(
                        "RagChatToolProvider '" + provider.getName()
                                + "' returned null tool policies");
            }
            Set<String> callbackNames = new HashSet<>();
            for (ToolCallback callback : provider.getToolCallbacks()) {
                if (callback == null || callback.getToolDefinition() == null
                        || callback.getToolMetadata() == null) {
                    throw new IllegalStateException(
                            "RagChatToolProvider '" + provider.getName()
                                    + "' returned an invalid callback");
                }
                ToolDefinition definition = callback.getToolDefinition();
                String name = definition.name();
                if (name == null || name.isBlank()
                        || !callbackNames.add(name) || !names.add(name)) {
                    throw new IllegalStateException(
                            "Duplicate or blank chat tool name: " + name);
                }
                if (callback.getToolMetadata().returnDirect()) {
                    throw new IllegalStateException(
                            "Chat tool must set returnDirect=false: " + name);
                }
                validateSchema(definition, provider.getName());
                RagChatToolPolicy policy = policies.get(name);
                if (policy == null && policies.containsKey(name)) {
                    throw new IllegalStateException(
                            "Null policy for chat tool: " + name);
                }
                if (policy == null) {
                    policy = defaultPolicy();
                }
                validatePolicy(name, policy);
                result.add(new RegisteredTool(
                        provider,
                        new PolicyToolCallback(callback, policy, executor),
                        policy));
            }
            for (String key : policies.keySet()) {
                if (key == null || key.isBlank()
                        || !callbackNames.contains(key)) {
                    throw new IllegalStateException(
                            "Unknown chat tool policy key: " + key);
                }
            }
        }
        return List.copyOf(result);
    }

    private RagChatToolPolicy defaultPolicy() {
        RagChatProperties.AgentProperties agent = properties.getAgent();
        return new RagChatToolPolicy(
                RagChatToolPolicy.Effect.READ_ONLY,
                Math.min(3, agent.getMaxToolCallsPerName()),
                Math.min(24_000, agent.getMaxToolResultCharacters()),
                Duration.ofSeconds(30));
    }

    private void validatePolicy(String name, RagChatToolPolicy policy) {
        RagChatProperties.AgentProperties agent = properties.getAgent();
        if (policy.effect() != RagChatToolPolicy.Effect.READ_ONLY
                || policy.maxCallsPerRequest() < 1
                || policy.maxCallsPerRequest() > agent.getMaxToolCallsPerName()
                || policy.maxResultCharacters() < 1_024
                || policy.maxResultCharacters()
                > agent.getMaxToolResultCharacters()
                || policy.timeout() == null
                || policy.timeout().isZero()
                || policy.timeout().isNegative()) {
            throw new IllegalStateException("Invalid policy for chat tool: " + name);
        }
    }

    private void validateSchema(ToolDefinition definition, String providerName) {
        String schema = definition.inputSchema();
        if (schema == null || schema.isBlank()) {
            throw new IllegalStateException(
                    "Empty input schema for provider " + providerName);
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(schema);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Invalid input schema for provider " + providerName, e);
        }
    }

    private boolean supports(RagChatToolProvider provider,
                             ChatMode mode,
                             String domainId) {
        if (!provider.supportedModes().contains(mode)) {
            return false;
        }
        Set<String> domains = provider.supportedDomains();
        if (domains.isEmpty()) {
            return true;
        }
        return domainId != null && !domainId.isBlank() && domains.contains(domainId);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record ProviderEntry(RagChatToolProvider provider, int order) {
    }

    private record RegisteredTool(
            RagChatToolProvider provider,
            ToolCallback callback,
            RagChatToolPolicy policy) {
    }

    private static final class BuiltinProvider implements RagChatToolProvider {
        private final String name;
        private final List<ToolCallback> callbacks;

        private BuiltinProvider(String name, List<ToolCallback> callbacks) {
            this.name = name;
            this.callbacks = List.copyOf(callbacks);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public List<ToolCallback> getToolCallbacks() {
            return callbacks;
        }
    }

    private static final class PolicyToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final RagChatToolPolicy policy;
        private final ThreadPoolExecutor executor;

        private PolicyToolCallback(
                ToolCallback delegate,
                RagChatToolPolicy policy,
                ThreadPoolExecutor executor) {
            this.delegate = delegate;
            this.policy = policy;
            this.executor = executor;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException(
                    "Missing server-owned chat tool request context");
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            if (toolContext == null || toolContext.getContext() == null
                    || !(toolContext.getContext().get(
                            RagChatToolContextKeys.REQUEST)
                    instanceof RagChatToolRequestContext)) {
                throw new IllegalStateException(
                        "Missing server-owned chat tool request context");
            }
            ChatExecutionBudget budget =
                    toolContext.getContext().get(ChatExecutionBudget.CONTEXT_KEY)
                            instanceof ChatExecutionBudget found
                            ? found
                            : null;
            if (budget != null
                    && !budget.tryReservePolicyToolCall(
                            delegate.getToolDefinition().name(),
                            policy.maxCallsPerRequest())) {
                return "{\"error\":\"tool_call_policy_exhausted\"}";
            }
            Future<String> future;
            try {
                future = executor.submit(
                        () -> delegate.call(toolInput, toolContext));
            } catch (RejectedExecutionException e) {
                return "{\"error\":\"tool_executor_saturated\"}";
            }
            try {
                long timeoutMillis = Math.max(1, policy.timeout().toMillis());
                Object requestValue = toolContext.getContext().get(
                        RagChatToolContextKeys.REQUEST);
                if (requestValue instanceof RagChatToolRequestContext request
                        && request.deadline() != null) {
                    long remaining = Duration.between(
                            java.time.Instant.now(),
                            request.deadline()).toMillis();
                    if (remaining <= 0) {
                        future.cancel(true);
                        return "{\"error\":\"tool_timeout\"}";
                    }
                    timeoutMillis = Math.min(timeoutMillis, remaining);
                }
                String value = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                if (value == null || value.length() <= policy.maxResultCharacters()) {
                    return value == null ? "" : value;
                }
                return "{\"error\":\"tool_result_too_large\"}";
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                return "{\"error\":\"tool_timeout\"}";
            } catch (Exception e) {
                future.cancel(true);
                return "{\"error\":\"tool_execution_failed\"}";
            }
        }
    }
}
