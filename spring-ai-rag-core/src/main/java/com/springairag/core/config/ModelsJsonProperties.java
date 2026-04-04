package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * External JSON-based model configuration.
 * Loaded from models.json in classpath.
 *
 * Phase 3: External models.json configuration center
 * - No restart needed to add/update models
 * - Supports hot-reload via @RefreshScope
 * - Priority lower than application.yml (yml overrides json)
 */
@Component
@ConfigurationProperties(prefix = "app.models-json")
public class ModelsJsonProperties {

    private static final Logger log = LoggerFactory.getLogger(ModelsJsonProperties.class);

    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    private RoutingConfig routing = new RoutingConfig();

    @PostConstruct
    public void init() {
        loadFromJson();
    }

    private void loadFromJson() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("models.json")) {
            if (is == null) {
                log.warn("models.json not found in classpath, skipping JSON-based configuration");
                return;
            }
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(is);

            // Parse providers
            var providersNode = node.get("providers");
            if (providersNode != null) {
                providersNode.fields().forEachRemaining(entry -> {
                    String name = entry.getKey();
                    var providerNode = entry.getValue();
                    ProviderConfig config = new ProviderConfig();
                    config.setDisplayName(providerNode.has("displayName") ? providerNode.get("displayName").asText() : name);
                    config.setEnabled(providerNode.has("enabled") && providerNode.get("enabled").asBoolean());
                    config.setPriority(providerNode.has("priority") ? providerNode.get("priority").asInt() : 99);

                    if (providerNode.has("chatModel")) {
                        var cm = providerNode.get("chatModel");
                        ChatModelConfig chatConfig = new ChatModelConfig();
                        chatConfig.setBaseUrl(cm.has("baseUrl") ? cm.get("baseUrl").asText() : "");
                        chatConfig.setModel(cm.has("model") ? cm.get("model").asText() : "");
                        chatConfig.setTemperature(cm.has("temperature") ? cm.get("temperature").asDouble() : 0.7);
                        chatConfig.setMaxTokens(cm.has("maxTokens") ? cm.get("maxTokens").asInt() : 4096);
                        config.setChatModel(chatConfig);
                    }

                    if (providerNode.has("embeddingModel")) {
                        var em = providerNode.get("embeddingModel");
                        EmbeddingModelConfig embConfig = new EmbeddingModelConfig();
                        embConfig.setBaseUrl(em.has("baseUrl") ? em.get("baseUrl").asText() : "");
                        embConfig.setModel(em.has("model") ? em.get("model").asText() : "");
                        embConfig.setDimension(em.has("dimension") ? em.get("dimension").asInt() : 1024);
                        config.setEmbeddingModel(embConfig);
                    }

                    providers.put(name, config);
                });
            }

            // Parse routing
            var routingNode = node.get("routing");
            if (routingNode != null) {
                if (routingNode.has("defaultProvider")) {
                    routing.setDefaultProvider(routingNode.get("defaultProvider").asText());
                }
                if (routingNode.has("fallbackChain")) {
                    routing.setFallbackChain(
                        new java.util.ArrayList<String>()
                    );
                    routingNode.get("fallbackChain").forEach(n ->
                        routing.getFallbackChain().add(n.asText())
                    );
                }
            }

            log.info("Loaded {} providers from models.json: {}",
                providers.size(), providers.keySet());

        } catch (Exception e) {
            log.error("Failed to load models.json: {}", e.getMessage());
        }
    }

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public RoutingConfig getRouting() {
        return routing;
    }

    public ProviderConfig getProvider(String name) {
        return providers.get(name);
    }

    public boolean hasProvider(String name) {
        return providers.containsKey(name);
    }

    public static class ProviderConfig {
        private String displayName;
        private boolean enabled;
        private int priority = 99;
        private ChatModelConfig chatModel;
        private EmbeddingModelConfig embeddingModel;

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getPriority() { return priority; }
        public void setPriority(int v) { this.priority = v; }
        public ChatModelConfig getChatModel() { return chatModel; }
        public void setChatModel(ChatModelConfig v) { this.chatModel = v; }
        public EmbeddingModelConfig getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(EmbeddingModelConfig v) { this.embeddingModel = v; }
    }

    public static class ChatModelConfig {
        private String baseUrl = "";
        private String model = "";
        private double temperature = 0.7;
        private int maxTokens = 4096;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double v) { this.temperature = v; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int v) { this.maxTokens = v; }
    }

    public static class EmbeddingModelConfig {
        private String baseUrl = "";
        private String model = "";
        private int dimension = 1024;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public int getDimension() { return dimension; }
        public void setDimension(int v) { this.dimension = v; }
    }

    public static class RoutingConfig {
        private String defaultProvider = "openai";
        private java.util.List<String> fallbackChain = new java.util.ArrayList<>();

        public String getDefaultProvider() { return defaultProvider; }
        public void setDefaultProvider(String v) { this.defaultProvider = v; }
        public java.util.List<String> getFallbackChain() { return fallbackChain; }
        public void setFallbackChain(java.util.List<String> v) { this.fallbackChain = v; }
    }
}
