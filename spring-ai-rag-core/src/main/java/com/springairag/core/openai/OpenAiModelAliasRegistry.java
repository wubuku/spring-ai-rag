package com.springairag.core.openai;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.config.RagOpenAiCompatibilityProperties;
import com.springairag.core.config.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 公开 OpenAI model alias 与内部 Chat pipeline 的只读注册表。
 */
@Component
@ConditionalOnProperty(
        prefix = "rag.openai-compatibility",
        name = "enabled",
        havingValue = "true")
public class OpenAiModelAliasRegistry {

    private static final Pattern ALIAS =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final int MAX_CANDIDATES = 16;

    private final Map<String, AliasDefinition> aliases;

    public OpenAiModelAliasRegistry(RagProperties properties) {
        RagOpenAiCompatibilityProperties source =
                properties.getOpenAiCompatibility();
        Map<String, AliasDefinition> validated = new LinkedHashMap<>();
        source.getModels().forEach((alias, model) -> {
            if (alias == null || !ALIAS.matcher(alias).matches()) {
                throw new IllegalStateException(
                        "Invalid rag.openai-compatibility model alias: " + alias);
            }
            if (model == null) {
                throw new IllegalStateException(
                        "Missing configuration for OpenAI model alias " + alias);
            }
            List<String> candidates = normalizeCandidates(
                    alias, model.getCandidates());
            validated.put(alias, new AliasDefinition(
                    alias,
                    candidates,
                    model.getMode(),
                    model.getMemory(),
                    model.isAllowRequestModeOverride(),
                    model.isAllowRequestMemoryOverride()));
        });
        if (validated.isEmpty()) {
            throw new IllegalStateException(
                    "rag.openai-compatibility.enabled=true requires at least one model alias");
        }
        this.aliases = Map.copyOf(validated);
    }

    public List<AliasDefinition> list() {
        return aliases.values().stream()
                .sorted(Comparator.comparing(AliasDefinition::alias))
                .toList();
    }

    public AliasDefinition require(String alias) {
        AliasDefinition definition = alias != null ? aliases.get(alias) : null;
        if (definition == null) {
            throw OpenAiProtocolException.modelNotFound(alias);
        }
        return definition;
    }

    public ResolvedAlias resolve(
            String alias,
            ChatMode requestedMode,
            String requestedMemory) {
        AliasDefinition definition = require(alias);
        ChatMode mode = definition.mode();
        if (requestedMode != null && requestedMode != mode) {
            if (!definition.allowRequestModeOverride()) {
                throw OpenAiProtocolException.invalid(
                        "rag.mode override is not allowed for model '" + alias + "'",
                        "rag.mode",
                        "unsupported_parameter");
            }
            mode = requestedMode;
        }

        MemoryMode memory = definition.memory();
        if (requestedMemory != null && !requestedMemory.isBlank()) {
            MemoryMode parsed;
            try {
                parsed = MemoryMode.valueOf(requestedMemory.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw OpenAiProtocolException.invalid(
                        "rag.memory must be STATELESS or SERVER",
                        "rag.memory",
                        "invalid_value");
            }
            if (parsed != memory && !definition.allowRequestMemoryOverride()) {
                throw OpenAiProtocolException.invalid(
                        "rag.memory override is not allowed for model '" + alias + "'",
                        "rag.memory",
                        "unsupported_parameter");
            }
            memory = parsed;
        }
        return new ResolvedAlias(
                definition.alias(),
                definition.candidates(),
                mode,
                memory);
    }

    private List<String> normalizeCandidates(
            String alias, List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_CANDIDATES) {
            throw new IllegalStateException(
                    "OpenAI alias '" + alias + "' has more than "
                            + MAX_CANDIDATES + " candidates");
        }
        java.util.LinkedHashSet<String> normalized =
                new java.util.LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(
                        "OpenAI alias '" + alias + "' contains a blank candidate");
            }
            normalized.add(value.trim());
        }
        return List.copyOf(new ArrayList<>(normalized));
    }

    public record AliasDefinition(
            String alias,
            List<String> candidates,
            ChatMode mode,
            MemoryMode memory,
            boolean allowRequestModeOverride,
            boolean allowRequestMemoryOverride) {
    }

    public record ResolvedAlias(
            String alias,
            List<String> candidates,
            ChatMode mode,
            MemoryMode memory) {
    }
}
