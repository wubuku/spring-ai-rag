package com.springairag.core.config;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private volatile Map<String, EmbeddingProfile> configuredProfiles = Map.of();

    public EmbeddingProfileRegistry(JdbcTemplate jdbcTemplate, RagProperties ragProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = ragProperties.getEmbedding();
    }

    public synchronized EmbeddingProfile initialize() {
        if (activeProfile != null) {
            return activeProfile;
        }
        Map<String, EmbeddingProfile> profiles = initializeConfiguredProfiles();
        EmbeddingProfile existing = profiles.get(properties.getProfileKey());
        if (existing == null) {
            throw new IllegalStateException(
                    "Default embedding profile is not configured: " + properties.getProfileKey());
        }
        if (!existing.enabled()) {
            throw new IllegalStateException(
                    "Embedding profile is disabled: " + existing.profileKey());
        }
        activeProfile = existing;
        return existing;
    }

    public synchronized Map<String, EmbeddingProfile> initializeConfiguredProfiles() {
        if (!configuredProfiles.isEmpty()) {
            return configuredProfiles;
        }
        validateConfiguredIdentity();

        Map<String, ProfileIdentity> identities = new LinkedHashMap<>();
        identities.put(properties.getProfileKey(), new ProfileIdentity(
                properties.getProvider(), properties.getModel(), properties.getModelRevision(),
                properties.getDimensions(), properties.getDistanceMetric(),
                properties.getNormalization(), true));
        properties.getProfiles().forEach((profileKey, profile) -> {
            validateConfiguredProfile(profileKey, profile);
            ProfileIdentity identity = new ProfileIdentity(
                    profile.getProvider(), profile.getModel(), profile.getModelRevision(),
                    profile.getDimensions(), profile.getDistanceMetric(),
                    profile.getNormalization(), profile.isEnabled());
            ProfileIdentity existing = identities.putIfAbsent(profileKey, identity);
            if (existing != null && !existing.equals(identity)) {
                throw new IllegalStateException(
                        "Embedding profile key is configured with conflicting identities: "
                                + profileKey);
            }
            if (existing != null) {
                identities.put(profileKey, identity);
            }
        });

        long enabledCount = identities.values().stream()
                .filter(ProfileIdentity::enabled)
                .count();
        if (enabledCount > 8) {
            throw new IllegalStateException(
                    "At most 8 embedding profiles may be enabled");
        }

        Map<String, EmbeddingProfile> registered = new LinkedHashMap<>();
        identities.forEach((profileKey, identity) -> {
            EmbeddingVectorColumns.columnFor(identity.dimensions());
            EmbeddingProfile profile = findByKey(profileKey);
            if (profile == null) {
                insertConfiguredProfile(profileKey, identity);
                profile = findByKey(profileKey);
            }
            validateMatchesConfiguration(profile, profileKey, identity);
            if (profile.enabled() != identity.enabled()) {
                if (!identity.enabled() && isReferencedByActiveCollection(profile.id())) {
                    throw new IllegalStateException(
                            "Cannot disable embedding profile while an active Collection uses it: "
                                    + profileKey);
                }
                jdbcTemplate.update(
                        "UPDATE rag_embedding_profiles SET enabled = ? WHERE id = ?",
                        identity.enabled(), profile.id());
                profile = new EmbeddingProfile(
                        profile.id(), profile.profileKey(), profile.provider(),
                        profile.modelName(), profile.modelRevision(), profile.dimensions(),
                        profile.distanceMetric(), profile.normalization(), identity.enabled());
            }
            registered.put(profileKey, profile);
        });
        for (EmbeddingProfile persisted : findAll()) {
            if (identities.containsKey(persisted.profileKey())) {
                continue;
            }
            if (isReferencedByActiveCollection(persisted.id())) {
                throw new IllegalStateException(
                        "Active Collection references an embedding profile without an exact runtime configuration: "
                                + persisted.profileKey());
            }
            if (persisted.enabled()) {
                jdbcTemplate.update(
                        "UPDATE rag_embedding_profiles SET enabled = FALSE "
                                + "WHERE id = ? AND enabled = TRUE",
                        persisted.id());
            }
        }
        configuredProfiles = Map.copyOf(registered);
        return configuredProfiles;
    }

    public List<EmbeddingProfile> findAll() {
        return jdbcTemplate.query(
                "SELECT id, profile_key, provider, model_name, model_revision, dimensions, "
                        + "distance_metric, normalization, enabled "
                        + "FROM rag_embedding_profiles ORDER BY id",
                (rs, rowNum) -> new EmbeddingProfile(
                        rs.getLong("id"), rs.getString("profile_key"),
                        rs.getString("provider"), rs.getString("model_name"),
                        rs.getString("model_revision"), rs.getInt("dimensions"),
                        rs.getString("distance_metric"), rs.getString("normalization"),
                        rs.getBoolean("enabled")));
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
        if (!profile.enabled()) {
            throw new IllegalStateException(
                    "Embedding profile is disabled: " + profile.profileKey());
        }
        return profile;
    }

    @Override
    public EmbeddingProfile findRequiredById(long profileId) {
        if (profileId <= 0) {
            throw new IllegalArgumentException("Embedding profile ID must be positive");
        }
        List<EmbeddingProfile> rows = jdbcTemplate.query(
                "SELECT id, profile_key, provider, model_name, model_revision, dimensions, "
                        + "distance_metric, normalization, enabled "
                        + "FROM rag_embedding_profiles WHERE id = ?",
                (rs, rowNum) -> new EmbeddingProfile(
                        rs.getLong("id"), rs.getString("profile_key"),
                        rs.getString("provider"), rs.getString("model_name"),
                        rs.getString("model_revision"), rs.getInt("dimensions"),
                        rs.getString("distance_metric"), rs.getString("normalization"),
                        rs.getBoolean("enabled")),
                profileId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Embedding profile not found: " + profileId);
        }
        EmbeddingProfile profile = rows.getFirst();
        if (!profile.enabled()) {
            throw new IllegalStateException(
                    "Embedding profile is disabled: " + profile.profileKey());
        }
        return profile;
    }

    public EmbeddingProfile findRequiredForCollection(long collectionId) {
        List<EmbeddingProfile> rows = jdbcTemplate.query("""
                SELECT p.id, p.profile_key, p.provider, p.model_name, p.model_revision,
                       p.dimensions, p.distance_metric, p.normalization, p.enabled
                FROM rag_collection c
                JOIN rag_embedding_profiles p ON p.id = c.active_embedding_profile_id
                WHERE c.id = ?
                """,
                (rs, rowNum) -> new EmbeddingProfile(
                        rs.getLong("id"), rs.getString("profile_key"),
                        rs.getString("provider"), rs.getString("model_name"),
                        rs.getString("model_revision"), rs.getInt("dimensions"),
                        rs.getString("distance_metric"), rs.getString("normalization"),
                        rs.getBoolean("enabled")),
                collectionId);
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "Collection has no registered active embedding profile: " + collectionId);
        }
        EmbeddingProfile profile = rows.getFirst();
        if (!profile.enabled()) {
            throw new IllegalStateException(
                    "Embedding profile is disabled: " + profile.profileKey());
        }
        return profile;
    }

    @Override
    public EmbeddingProfile getProfileForCollection(long collectionId) {
        return findRequiredForCollection(collectionId);
    }

    public Map<String, EmbeddingProfile> getConfiguredProfiles() {
        return configuredProfiles.isEmpty()
                ? initializeConfiguredProfiles() : configuredProfiles;
    }

    private boolean isReferencedByActiveCollection(long profileId) {
        Boolean referenced = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM rag_collection
                    WHERE active_embedding_profile_id = ?
                      AND enabled = TRUE
                      AND deleted = FALSE
                      AND purged_at IS NULL
                )
                """, Boolean.class, profileId);
        return Boolean.TRUE.equals(referenced);
    }

    private void insertConfiguredProfile() {
        insertConfiguredProfile(properties.getProfileKey(), new ProfileIdentity(
                properties.getProvider(), properties.getModel(), properties.getModelRevision(),
                properties.getDimensions(), properties.getDistanceMetric(),
                properties.getNormalization(), true));
    }

    private void insertConfiguredProfile(String profileKey, ProfileIdentity identity) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO rag_embedding_profiles " +
                            "(profile_key, provider, model_name, model_revision, dimensions, " +
                            "distance_metric, normalization, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    profileKey,
                    identity.provider(),
                    identity.model(),
                    identity.modelRevision(),
                    identity.dimensions(),
                    identity.distanceMetric(),
                    identity.normalization(),
                    identity.enabled());
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

    private void validateConfiguredProfile(
            String profileKey, RagEmbeddingProfileProperties profile) {
        if (isBlank(profileKey) || profileKey.length() > 128 || profile == null
                || isBlank(profile.getProvider()) || isBlank(profile.getModel())
                || isBlank(profile.getModelRevision()) || isBlank(profile.getDistanceMetric())
                || isBlank(profile.getNormalization())) {
            throw new IllegalStateException(
                    "Embedding profile configuration is incomplete: " + profileKey);
        }
        if (!"COSINE".equals(profile.getDistanceMetric())) {
            throw new IllegalStateException(
                    "Only COSINE embedding distance is supported in this release");
        }
        EmbeddingVectorColumns.columnFor(profile.getDimensions());
        if (profile.isEnabled()
                && (isBlank(profile.getBaseUrl()) || isBlank(profile.getApiKey()))) {
            throw new IllegalStateException(
                    "Enabled embedding profile requires base-url and api-key: " + profileKey);
        }
    }

    private void validateMatchesConfiguration(EmbeddingProfile profile) {
        validateMatchesConfiguration(profile, properties.getProfileKey(), new ProfileIdentity(
                properties.getProvider(), properties.getModel(), properties.getModelRevision(),
                properties.getDimensions(), properties.getDistanceMetric(),
                properties.getNormalization(), true));
    }

    private void validateMatchesConfiguration(
            EmbeddingProfile profile, String profileKey, ProfileIdentity identity) {
        if (profile == null) {
            throw new IllegalStateException(
                    "Failed to create embedding profile: " + profileKey);
        }
        requireEqual("profileKey", profileKey, profile.profileKey());
        requireEqual("provider", identity.provider(), profile.provider());
        requireEqual("model", identity.model(), profile.modelName());
        requireEqual("modelRevision", identity.modelRevision(), profile.modelRevision());
        requireEqual("dimensions", identity.dimensions(), profile.dimensions());
        requireEqual("distanceMetric", identity.distanceMetric(), profile.distanceMetric());
        requireEqual("normalization", identity.normalization(), profile.normalization());
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

    private record ProfileIdentity(
            String provider,
            String model,
            String modelRevision,
            int dimensions,
            String distanceMetric,
            String normalization,
            boolean enabled) {
    }
}
