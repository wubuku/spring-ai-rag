package com.springairag.demo.sql;

import com.springairag.api.service.RagChatToolPolicy;
import com.springairag.api.service.RagChatToolProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Registers the SQL example as a normal server-owned read-only tool.
 */
@Component
public final class DemoSqlToolProvider implements RagChatToolProvider {

    private final ReadOnlyOrderLookupTool tool;

    public DemoSqlToolProvider(ReadOnlyOrderLookupTool tool) {
        this.tool = tool;
    }

    @Override
    public String getName() {
        return "demo-read-only-sql";
    }

    @Override
    public List<ToolCallback> getToolCallbacks() {
        return List.of(tool);
    }

    @Override
    public Map<String, RagChatToolPolicy> getToolPolicies() {
        return Map.of(
                ReadOnlyOrderLookupTool.NAME,
                new RagChatToolPolicy(
                        RagChatToolPolicy.Effect.READ_ONLY,
                        2,
                        8_000,
                        Duration.ofSeconds(2)));
    }
}
