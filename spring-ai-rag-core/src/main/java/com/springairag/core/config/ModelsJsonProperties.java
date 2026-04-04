package com.springairag.core.config;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * External JSON-based model configuration.
 * Loaded from models.json in classpath.
 *
 * <p>Phase 3: External models.json configuration center
 * - No restart needed to add/update models
 * - API keys support ${ENV_VAR} placeholders resolved from environment variables
 * - Priority: environment variables > models.json > defaults
 */
@Component
public class ModelsJsonProperties {

    private static final Logger log = LoggerFactory.getLogger(ModelsJsonProperties.class);

    /** Pattern to match ${ENV_VAR} placeholders */
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

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

                    // Parse apiKey (may contain ${ENV_VAR} placeholder)
                    if (providerNode.has("apiKey")) {
                        String apiKeyRaw = providerNode.get("apiKey").asText();
                        config.setApiKey(resolveEnvVar(apiKeyRaw));
                    }

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
                    routing.setFallbackChain(new java.util.ArrayList<String>());
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

    /**
     * Resolve ${ENV_VAR} placeholders from environment variables.
     * Supports patterns like: ${OPENAI_API_KEY}, ${ANTHROPIC_API_KEY:dummy}
     */
    private String resolveEnvVar(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        Matcher matcher = ENV_VAR_PATTERN.matcher(value);
        if (!matcher.find()) {
            // No placeholder, return as-is
            return value;
        }
        // Reset and process
        StringBuffer sb = new StringBuffer();
        matcher.reset();
        while (matcher.find()) {
            String envVar = matcher.group(1);
            String defaultValue = "";
            // Support default value syntax: ${ENV_VAR:default}
            int colonIdx = envVar.indexOf(':');
            if (colonIdx > 0) {
                defaultValue = envVar.substring(colonIdx + 1);
                envVar = envVar.substring(0, colonIdx);
            }
            String envValue = System.getenv(envVar);
            String replacement = StringUtils.hasText(envValue) ? envValue : defaultValue;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
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

    // ========================================================================
    // Nested config classes
    // ========================================================================

    public static class ProviderConfig {
        private String displayName;
        private boolean enabled;
        private int priority = 99;
        private String apiKey;  // May contain ${ENV_VAR} or resolved value
        private ChatModelConfig chatModel;
        private EmbeddingModelConfig embeddingModel;

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getPriority() { return priority; }
        public void setPriority(int v) { this.priority = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
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
