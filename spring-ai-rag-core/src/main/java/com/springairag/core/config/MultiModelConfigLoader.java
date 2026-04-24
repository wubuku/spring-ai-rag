package com.springairag.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        Path path = Path.of(configFile);
        if (!Files.exists(path)) {
            log.info("External models.json not found at '{}'; using YAML config only", configFile);
            return;
        }

        try {
            String json = Files.readString(path);
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            ModelsJsonRoot root = mapper.readValue(json, ModelsJsonRoot.class);

            if (root.models != null) {
                applyModelsConfig(root.models);
            }

            log.info("External models.json loaded from '{}'; YAML config overridden", configFile);

        } catch (IOException e) {
            log.error("Failed to load external models.json from '{}': {}", configFile, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void applyModelsConfig(ModelsJsonRoot.ModelsJson jsonModels) {
        if (jsonModels.providers != null) {
            jsonModels.providers.forEach((providerId, providerJson) -> {
                properties.getProviders().put(providerId, toProviderConfig(providerId, providerJson));
            });
        }

        if (jsonModels.chatModel != null) {
            properties.setChatModel(toModelRouting(jsonModels.chatModel));
        }

        if (jsonModels.embeddingModel != null) {
            properties.setEmbeddingModel(toModelRouting(jsonModels.embeddingModel));
        }
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
                m.dimension
        );
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

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                ModelsJson that = (ModelsJson) o;
                return Objects.equals(providers, that.providers)
                        && Objects.equals(chatModel, that.chatModel)
                        && Objects.equals(embeddingModel, that.embeddingModel);
            }

            @Override
            public int hashCode() {
                return Objects.hash(providers, chatModel, embeddingModel);
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
                        && Objects.equals(dimension, that.dimension);
            }

            @Override
            public int hashCode() {
                return Objects.hash(id, name, type, reasoning, inputModalities,
                        cost, contextWindow, maxTokens, dimension);
            }

            @Override
            public String toString() {
                return "ModelJson{id=" + id + ", name=" + name + ", type=" + type
                        + ", reasoning=" + reasoning + ", inputModalities=" + inputModalities
                        + ", cost=" + cost + ", contextWindow=" + contextWindow
                        + ", maxTokens=" + maxTokens + ", dimension=" + dimension + "}";
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
