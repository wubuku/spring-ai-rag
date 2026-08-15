package com.springairag.core.config;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 注册并校验活动 Embedding Profile。
 */
@Component
public class EmbeddingProfileRegistry implements EmbeddingProfileProvider {

    public static final String DEFAULT_PROFILE_KEY = "siliconflow-bge-m3-1024-v1";
    private static final String DEFAULT_PROVIDER = "siliconflow";
    private static final String DEFAULT_MODEL = "BAAI/bge-m3";

    private final JdbcTemplate jdbcTemplate;
    private final RagEmbeddingProperties properties;
    private volatile EmbeddingProfile activeProfile;

    public EmbeddingProfileRegistry(JdbcTemplate jdbcTemplate, RagProperties ragProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = ragProperties.getEmbedding();
    }

    public synchronized EmbeddingProfile initialize() {
        if (activeProfile != null) {
            return activeProfile;
        }
        validateConfiguredIdentity();
        EmbeddingVectorColumns.columnFor(properties.getDimensions());

        EmbeddingProfile existing = findByKey(properties.getProfileKey());
        if (existing == null) {
            insertConfiguredProfile();
            existing = findByKey(properties.getProfileKey());
        }
        validateMatchesConfiguration(existing);
        if (!existing.enabled()) {
            throw new IllegalStateException(
                    "Embedding profile is disabled: " + existing.profileKey());
        }
        activeProfile = existing;
        return existing;
    }

    @Override
    public EmbeddingProfile getActiveProfile() {
        EmbeddingProfile current = activeProfile;
        return current != null ? current : initialize();
    }

    public EmbeddingProfile findRequiredByKey(String profileKey) {
        EmbeddingProfile profile = findByKey(profileKey);
        if (profile == null) {
            throw new IllegalStateException("Embedding profile not found: " + profileKey);
        }
        return profile;
    }

    private void insertConfiguredProfile() {
        try {
            jdbcTemplate.update(
                    "INSERT INTO rag_embedding_profiles " +
                            "(profile_key, provider, model_name, model_revision, dimensions, " +
                            "distance_metric, normalization, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, true)",
                    properties.getProfileKey(),
                    properties.getProvider(),
                    properties.getModel(),
                    properties.getModelRevision(),
                    properties.getDimensions(),
                    properties.getDistanceMetric(),
                    properties.getNormalization());
        } catch (DuplicateKeyException ignored) {
            // 多实例并发注册时由唯一约束收敛，随后重新读取并校验。
        }
    }

    private EmbeddingProfile findByKey(String profileKey) {
        List<EmbeddingProfile> rows = jdbcTemplate.query(
                "SELECT id, profile_key, provider, model_name, model_revision, dimensions, " +
                        "distance_metric, normalization, enabled " +
                        "FROM rag_embedding_profiles WHERE profile_key = ?",
                (rs, rowNum) -> new EmbeddingProfile(
                        rs.getLong("id"),
                        rs.getString("profile_key"),
                        rs.getString("provider"),
                        rs.getString("model_name"),
                        rs.getString("model_revision"),
                        rs.getInt("dimensions"),
                        rs.getString("distance_metric"),
                        rs.getString("normalization"),
                        rs.getBoolean("enabled")),
                profileKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void validateConfiguredIdentity() {
        if (isBlank(properties.getProfileKey())
                || isBlank(properties.getProvider())
                || isBlank(properties.getModel())
                || isBlank(properties.getModelRevision())
                || isBlank(properties.getDistanceMetric())
                || isBlank(properties.getNormalization())) {
            throw new IllegalStateException("Embedding profile identity fields must not be blank");
        }
        if (!"COSINE".equals(properties.getDistanceMetric())) {
            throw new IllegalStateException(
                    "Only COSINE embedding distance is supported in this release");
        }
        boolean builtInIdentity = DEFAULT_PROVIDER.equals(properties.getProvider())
                && DEFAULT_MODEL.equals(properties.getModel())
                && properties.getDimensions() == 1024
                && "COSINE".equals(properties.getDistanceMetric())
                && "PROVIDER_DEFAULT".equals(properties.getNormalization());
        if (DEFAULT_PROFILE_KEY.equals(properties.getProfileKey()) && !builtInIdentity) {
            throw new IllegalStateException(
                    "RAG_EMBEDDING_PROFILE_KEY must be explicit when overriding embedding identity");
        }
    }

    private void validateMatchesConfiguration(EmbeddingProfile profile) {
        if (profile == null) {
            throw new IllegalStateException(
                    "Failed to create embedding profile: " + properties.getProfileKey());
        }
        requireEqual("provider", properties.getProvider(), profile.provider());
        requireEqual("model", properties.getModel(), profile.modelName());
        requireEqual("modelRevision", properties.getModelRevision(), profile.modelRevision());
        requireEqual("dimensions", properties.getDimensions(), profile.dimensions());
        requireEqual("distanceMetric", properties.getDistanceMetric(), profile.distanceMetric());
        requireEqual("normalization", properties.getNormalization(), profile.normalization());
    }

    private void requireEqual(String field, Object configured, Object stored) {
        if (!Objects.equals(configured, stored)) {
            throw new IllegalStateException(
                    "Embedding profile identity mismatch for " + field
                            + ": configured=" + configured + ", stored=" + stored);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
