package com.springairag.api.service;

import com.springairag.api.enums.ChatMode;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-owned extension point for read-only tools exposed to AGENT Chat.
 */
public interface RagChatToolProvider {

    String getName();

    default int getOrder() {
        return 0;
    }

    default Set<ChatMode> supportedModes() {
        return Set.of(ChatMode.AGENT);
    }

    default Set<String> supportedDomains() {
        return Set.of();
    }

    List<ToolCallback> getToolCallbacks();

    default Map<String, RagChatToolPolicy> getToolPolicies() {
        return Map.of();
    }
}
