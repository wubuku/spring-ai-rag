package com.springairag.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 外部 models.json 配置文件加载器。
 *
 * <p>当外部 JSON 文件存在时，完全覆盖 YAML 配置（不支持合并）。
 * JSON 与 YAML 结构完全一致（仅 camelCase vs snake_case）。
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

    // ─── JSON 结构映射（camelCase）─────────────────────────────

    public static class ModelsJsonRoot {
        public ModelsJson models;

        public static class ModelsJson {
            public java.util.Map<String, ProviderJson> providers;
            public RoutingJson chatModel;
            public RoutingJson embeddingModel;
        }

        public static class ProviderJson {
            public String displayName;
            public String baseUrl;
            public String apiKey;
            public String apiType;
            public Boolean enabled;
            public Integer priority;
            public List<ModelJson> models;
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

            public static class CostJson {
                public Double input;
                public Double output;
                public Double cacheRead;
                public Double cacheWrite;
            }
        }

        public static class RoutingJson {
            public String primary;
            public List<String> fallbacks;
        }
    }
}
