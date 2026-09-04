package com.springairag.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * External models.json Configuration File Loader.
 *
 * <p>When an external JSON file exists, it completely overrides YAML configuration (no merging).
 * JSON and YAML structures are identical (only camelCase vs snake_case).
 */
@Component
public class MultiModelConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(MultiModelConfigLoader.class);

    private final MultiModelProperties properties;

    @Autowired
    public MultiModelConfigLoader(MultiModelProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void loadExternalJsonIfPresent() {
        String configFile = properties.getConfigFile();
        if (configFile == null || configFile.isBlank()) {
            log.info("app.models.configFile not set; using YAML config only");
            return;
        }

        Path path;
        try {
            path = configFile.startsWith("file:")
                    ? Path.of(URI.create(configFile))
                    : Path.of(configFile);
        } catch (IllegalArgumentException e) {
            log.error("Invalid external models.json path '{}': {}", configFile, e.getMessage());
            return;
        }
        if (!Files.exists(path)) {
            log.info("External models.json not found at '{}'; using YAML config only", configFile);
            return;
        }

        try {
            String json = Files.readString(path);
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            JsonNode root = mapper.readTree(json);
            validateSchema(path, root);

            ModelsJsonRoot parsed = mapper.treeToValue(root, ModelsJsonRoot.class);

            if (parsed.models != null) {
                applyModelsConfig(parsed.models);
            }

            log.info("External models.json loaded from '{}'; YAML config overridden", configFile);

        } catch (IOException e) {
            log.error("Failed to load external models.json from '{}': {}", configFile, e.getMessage());
        }
    }

    // ─── Schema validation ──────────────────────────────────────────

    /**
     * 已知的合法键全集（所有层级键均为 camelCase，不允许连字符）。
     */
    private static final Set<String> KNOWN_KEYS = Set.copyOf(List.of(
            "models", "providers", "chatModel", "embeddingModel", "legacyCapabilities",
            "displayName", "baseUrl", "apiKey", "apiType", "enabled", "priority",
            "id", "name", "type", "reasoning", "inputModalities", "cost", "contextWindow",
            "maxTokens", "dimension", "capabilities", "streaming", "toolCalling",
            "primary", "fallbacks"));

    /**
     * 校验外部 models.json 的键名，尽早暴露静默失效的拼写错误：
     *
     * <ul>
     *   <li>键包含连字符（如 {@code chat-model}、{@code input-modalities}，YAML 的
     *       kebab-case 习惯误用到 JSON）会令对应配置整体静默失效并回退 legacy 默认，
     *       属于 fail-closed 场景，直接抛出异常阻止启动；</li>
     *   <li>未知键（可能是拼写错误或废弃字段）记录 WARN 并列出完整路径，不阻断加载。</li>
     * </ul>
     */
    private void validateSchema(Path path, JsonNode root) {
        List<String> kebabErrors = new ArrayList<>();
        Set<String> unknownKeys = new LinkedHashSet<>();
        collectKeyIssues(root, "", path, kebabErrors, unknownKeys);

        if (!kebabErrors.isEmpty()) {
            throw new IllegalStateException(
                    "External models.json '" + path + "' uses kebab-case keys that are "
                            + "silently ignored; use camelCase instead. Offending keys: "
                            + kebabErrors);
        }
        if (!unknownKeys.isEmpty()) {
            log.warn("External models.json '{}' contains unknown keys (they are ignored): {}",
                    path, unknownKeys);
        }
    }

    private void collectKeyIssues(
            JsonNode node,
            String prefix,
            Path path,
            List<String> kebabErrors,
            Set<String> unknownKeys) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                String childPath = prefix.isEmpty() ? key : prefix + "." + key;
                if (key.contains("-")) {
                    kebabErrors.add(childPath + " (did you mean '"
                            + kebabToCamel(key) + "'?)");
                } else if (!KNOWN_KEYS.contains(key)) {
                    unknownKeys.add(childPath);
                }
                collectKeyIssues(entry.getValue(), childPath, path, kebabErrors, unknownKeys);
            });
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectKeyIssues(item, prefix + "[]", path, kebabErrors, unknownKeys);
            }
        }
    }

    private static String kebabToCamel(String key) {
        StringBuilder camel = new StringBuilder(key.length());
        boolean upperNext = false;
        for (char c : key.toCharArray()) {
            if (c == '-') {
                upperNext = true;
            } else if (upperNext) {
                camel.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                camel.append(c);
            }
        }
        return camel.toString();
    }

    private void applyModelsConfig(ModelsJsonRoot.ModelsJson jsonModels) {
        // External JSON is a full override, not a merge with application.yml.
        properties.setProviders(new LinkedHashMap<>());
        if (jsonModels.providers != null) {
            jsonModels.providers.forEach((providerId, providerJson) -> {
                properties.getProviders().put(providerId, toProviderConfig(providerId, providerJson));
            });
        }

        properties.setChatModel(jsonModels.chatModel != null
                ? toModelRouting(jsonModels.chatModel)
                : null);
        properties.setEmbeddingModel(jsonModels.embeddingModel != null
                ? toModelRouting(jsonModels.embeddingModel)
                : null);
        properties.setLegacyCapabilities(jsonModels.legacyCapabilities != null
                ? jsonModels.legacyCapabilities.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                java.util.Map.Entry::getKey,
                                entry -> toCapabilities(entry.getValue()),
                                (left, right) -> right,
                                LinkedHashMap::new))
                : java.util.Map.of());
    }

    private MultiModelProperties.ProviderConfig toProviderConfig(String providerId, ModelsJsonRoot.ProviderJson p) {
        return new MultiModelProperties.ProviderConfig(
                p.displayName,
                p.baseUrl,
                p.apiKey,
                p.apiType,
                p.enabled != null ? p.enabled : true,
                p.priority,
                p.models != null
                        ? p.models.stream().map(this::toModelItem).toList()
                        : List.of()
        );
    }

    private MultiModelProperties.ModelItem toModelItem(ModelsJsonRoot.ModelJson m) {
        MultiModelProperties.ModelCost cost = null;
        if (m.cost != null) {
            cost = new MultiModelProperties.ModelCost(
                    m.cost.input != null ? m.cost.input : 0,
                    m.cost.output != null ? m.cost.output : 0,
                    m.cost.cacheRead != null ? m.cost.cacheRead : 0,
                    m.cost.cacheWrite != null ? m.cost.cacheWrite : 0
            );
        }
        return new MultiModelProperties.ModelItem(
                m.id,
                m.name,
                m.type,
                m.reasoning != null && m.reasoning,
                m.inputModalities,
                cost,
                m.contextWindow,
                m.maxTokens,
                m.dimension,
                toCapabilities(m.capabilities)
        );
    }

    private MultiModelProperties.ModelCapabilities toCapabilities(
            ModelsJsonRoot.CapabilitiesJson capabilities) {
        return capabilities == null
                ? MultiModelProperties.ModelCapabilities.defaults()
                : new MultiModelProperties.ModelCapabilities(
                        capabilities.streaming, capabilities.toolCalling);
    }

    private MultiModelProperties.ModelRouting toModelRouting(ModelsJsonRoot.RoutingJson r) {
        return new MultiModelProperties.ModelRouting(
                r.primary,
                r.fallbacks != null ? r.fallbacks : List.of()
        );
    }

    // ─── JSON Structure Mapping (camelCase) ─────────────────────────

    public static class ModelsJsonRoot {
        public ModelsJson models;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ModelsJsonRoot that = (ModelsJsonRoot) o;
            return Objects.equals(models, that.models);
        }

        @Override
        public int hashCode() {
            return Objects.hash(models);
        }

        @Override
        public String toString() {
            return "ModelsJsonRoot{models=" + models + "}";
        }

        public static class ModelsJson {
            public java.util.Map<String, ProviderJson> providers;
            public RoutingJson chatModel;
            public RoutingJson embeddingModel;
            public java.util.Map<String, CapabilitiesJson> legacyCapabilities;

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                ModelsJson that = (ModelsJson) o;
                return Objects.equals(providers, that.providers)
                        && Objects.equals(chatModel, that.chatModel)
                        && Objects.equals(embeddingModel, that.embeddingModel)
                        && Objects.equals(legacyCapabilities, that.legacyCapabilities);
            }

            @Override
            public int hashCode() {
                return Objects.hash(providers, chatModel, embeddingModel,
                        legacyCapabilities);
            }

            @Override
            public String toString() {
                return "ModelsJson{providers=" + providers + ", chatModel=" + chatModel
                        + ", embeddingModel=" + embeddingModel + "}";
            }
        }

        public static class ProviderJson {
            public String displayName;
            public String baseUrl;
            public String apiKey;
            public String apiType;
            public Boolean enabled;
            public Integer priority;
            public List<ModelJson> models;

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                ProviderJson that = (ProviderJson) o;
                return Objects.equals(displayName, that.displayName)
                        && Objects.equals(baseUrl, that.baseUrl)
                        && Objects.equals(apiKey, that.apiKey)
                        && Objects.equals(apiType, that.apiType)
                        && Objects.equals(enabled, that.enabled)
                        && Objects.equals(priority, that.priority)
                        && Objects.equals(models, that.models);
            }

            @Override
            public int hashCode() {
                return Objects.hash(displayName, baseUrl, apiKey, apiType,
                        enabled, priority, models);
            }

            @Override
            public String toString() {
                return "ProviderJson{displayName=" + displayName + ", baseUrl=" + baseUrl
                        + ", apiType=" + apiType + ", enabled=" + enabled
                        + ", priority=" + priority + ", models=" + models + "}";
            }
        }

        public static class ModelJson {
            public String id;
            public String name;
            public String type;
            public Boolean reasoning;
            public List<String> inputModalities;
            public CostJson cost;
            public Integer contextWindow;
            public Integer maxTokens;
            public Integer dimension;
            public CapabilitiesJson capabilities;

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                ModelJson that = (ModelJson) o;
                return Objects.equals(id, that.id)
                        && Objects.equals(name, that.name)
                        && Objects.equals(type, that.type)
                        && Objects.equals(reasoning, that.reasoning)
                        && Objects.equals(inputModalities, that.inputModalities)
                        && Objects.equals(cost, that.cost)
                        && Objects.equals(contextWindow, that.contextWindow)
                        && Objects.equals(maxTokens, that.maxTokens)
                        && Objects.equals(dimension, that.dimension)
                        && Objects.equals(capabilities, that.capabilities);
            }

            @Override
            public int hashCode() {
                return Objects.hash(id, name, type, reasoning, inputModalities,
                        cost, contextWindow, maxTokens, dimension, capabilities);
            }

            @Override
            public String toString() {
                return "ModelJson{id=" + id + ", name=" + name + ", type=" + type
                        + ", reasoning=" + reasoning + ", inputModalities=" + inputModalities
                        + ", cost=" + cost + ", contextWindow=" + contextWindow
                        + ", maxTokens=" + maxTokens + ", dimension=" + dimension
                        + ", capabilities=" + capabilities + "}";
            }

            public static class CostJson {
                public Double input;
                public Double output;
                public Double cacheRead;
                public Double cacheWrite;

                @Override
                public boolean equals(Object o) {
                    if (this == o) return true;
                    if (o == null || getClass() != o.getClass()) return false;
                    CostJson that = (CostJson) o;
                    return Objects.equals(input, that.input)
                            && Objects.equals(output, that.output)
                            && Objects.equals(cacheRead, that.cacheRead)
                            && Objects.equals(cacheWrite, that.cacheWrite);
                }

                @Override
                public int hashCode() {
                    return Objects.hash(input, output, cacheRead, cacheWrite);
                }

                @Override
                public String toString() {
                    return "CostJson{input=" + input + ", output=" + output
                            + ", cacheRead=" + cacheRead + ", cacheWrite=" + cacheWrite + "}";
                }
            }
        }

        public static class CapabilitiesJson {
            public Boolean streaming;
            public Boolean toolCalling;

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                CapabilitiesJson that = (CapabilitiesJson) o;
                return Objects.equals(streaming, that.streaming)
                        && Objects.equals(toolCalling, that.toolCalling);
            }

            @Override
            public int hashCode() {
                return Objects.hash(streaming, toolCalling);
            }

            @Override
            public String toString() {
                return "CapabilitiesJson{streaming=" + streaming
                        + ", toolCalling=" + toolCalling + "}";
            }
        }

        public static class RoutingJson {
            public String primary;
            public List<String> fallbacks;

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                RoutingJson that = (RoutingJson) o;
                return Objects.equals(primary, that.primary)
                        && Objects.equals(fallbacks, that.fallbacks);
            }

            @Override
            public int hashCode() {
                return Objects.hash(primary, fallbacks);
            }

            @Override
            public String toString() {
                return "RoutingJson{primary=" + primary + ", fallbacks=" + fallbacks + "}";
            }
        }
    }
}
