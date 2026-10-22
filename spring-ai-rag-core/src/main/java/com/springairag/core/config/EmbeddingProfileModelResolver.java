package com.springairag.core.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 将已注册 Profile 身份解析为专属 EmbeddingModel 实例。 */
@Component
public class EmbeddingProfileModelResolver {

    private final EmbeddingProfileRegistry profileRegistry;
    private final EmbeddingModelConfig modelConfig;
    private final RagEmbeddingProperties properties;
    private final EmbeddingModel defaultModel;
    private final Map<Long, EmbeddingModel> modelsByProfileId = new ConcurrentHashMap<>();

    public EmbeddingProfileModelResolver(
            EmbeddingProfileRegistry profileRegistry,
            EmbeddingModelConfig modelConfig,
            RagProperties ragProperties,
            @Qualifier("embeddingModel") EmbeddingModel defaultModel) {
        this.profileRegistry = profileRegistry;
        this.modelConfig = modelConfig;
        this.properties = ragProperties.getEmbedding();
        this.defaultModel = defaultModel;
    }

    public synchronized void initialize() {
        Map<String, EmbeddingProfile> profiles =
                profileRegistry.initializeConfiguredProfiles();
        for (EmbeddingProfile profile : profiles.values()) {
            if (!profile.enabled()) {
                continue;
            }
            EmbeddingModel model;
            if (profile.profileKey().equals(properties.getProfileKey())) {
                model = defaultModel;
            } else {
                RagEmbeddingProfileProperties configured =
                        properties.getProfiles().get(profile.profileKey());
                if (configured == null || !configured.isEnabled()) {
                    throw new IllegalStateException(
                            "Enabled embedding profile has no exact model configuration: "
                                    + profile.profileKey());
                }
                model = modelConfig.createModel(configured);
            }
            modelsByProfileId.put(profile.id(), model);
        }
    }

    public EmbeddingModel resolve(EmbeddingProfile profile) {
        if (profile == null || !profile.enabled()) {
            throw new IllegalStateException("An enabled embedding profile is required");
        }
        EmbeddingModel model = modelsByProfileId.get(profile.id());
        if (model == null) {
            initialize();
            model = modelsByProfileId.get(profile.id());
        }
        if (model == null) {
            throw new IllegalStateException(
                    "No EmbeddingModel is configured for Profile: " + profile.profileKey());
        }
        return model;
    }
}
