package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 Embedding Profile 注册表的启动校验矩阵：身份字段、COSINE 限定、
 * 内置 profile key 保护、并发注册收敛、存储配置匹配与禁用拒绝。
 */
class EmbeddingProfileRegistryTest {

    private static final String SELECT_SQL =
            "SELECT id, profile_key, provider, model_name, model_revision, dimensions, " +
                    "distance_metric, normalization, enabled " +
                    "FROM rag_embedding_profiles WHERE profile_key = ?";

    private JdbcTemplate jdbcTemplate;
    private RagEmbeddingProperties properties;
    private EmbeddingProfileRegistry registry;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        RagProperties ragProperties = new RagProperties();
        properties = ragProperties.getEmbedding();
        registry = new EmbeddingProfileRegistry(jdbcTemplate, ragProperties);
    }

    private EmbeddingProfile storedProfile() {
        return new EmbeddingProfile(
                7L,
                properties.getProfileKey(),
                properties.getProvider(),
                properties.getModel(),
                properties.getModelRevision(),
                properties.getDimensions(),
                properties.getDistanceMetric(),
                properties.getNormalization(),
                true);
    }

    private void stubQueryReturning(EmbeddingProfile profile) {
        when(jdbcTemplate.query(eq(SELECT_SQL),
                any(RowMapper.class), eq(properties.getProfileKey())))
                .thenReturn(profile == null ? List.of() : List.of(profile));
    }

    @Test
    void initializeRejectsBlankIdentityFields() {
        properties.setProfileKey(" ");

        assertThrows(IllegalStateException.class, registry::initialize);
    }

    @Test
    void initializeRejectsNonCosineDistance() {
        properties.setDistanceMetric("L2");

        IllegalStateException error = assertThrows(
                IllegalStateException.class, registry::initialize);
        assertEquals("Only COSINE embedding distance is supported in this release",
                error.getMessage());
    }

    @Test
    void initializeProtectsBuiltInProfileKeyFromIdentityOverride() {
        properties.setProvider("other-provider");

        IllegalStateException error = assertThrows(
                IllegalStateException.class, registry::initialize);
        assertEquals("RAG_EMBEDDING_PROFILE_KEY must be explicit when "
                + "overriding embedding identity", error.getMessage());
    }

    @Test
    void initializeAllowsCustomKeyWithOverriddenIdentity() {
        properties.setProfileKey("custom-key");
        properties.setProvider("other-provider");
        stubQueryReturning(new EmbeddingProfile(
                8L, "custom-key", "other-provider", properties.getModel(),
                properties.getModelRevision(), properties.getDimensions(),
                properties.getDistanceMetric(), properties.getNormalization(),
                true));

        EmbeddingProfile profile = registry.initialize();

        assertEquals("custom-key", profile.profileKey());
    }

    @Test
    void initializeInsertsMissingProfileThenReadsItBack() {
        EmbeddingProfile stored = storedProfile();
        when(jdbcTemplate.query(eq(SELECT_SQL), any(RowMapper.class), eq(properties.getProfileKey())))
                .thenReturn(List.of())
                .thenReturn(List.of(stored));

        EmbeddingProfile profile = registry.initialize();

        assertEquals(7L, profile.id());
        // 缺失时插入一次，随后回读。
        verify(jdbcTemplate).update(any(String.class), any(Object[].class));
    }

    @Test
    void initializeToleratesDuplicateKeyFromConcurrentRegistration() {
        EmbeddingProfile stored = storedProfile();
        when(jdbcTemplate.query(eq(SELECT_SQL), any(RowMapper.class), eq(properties.getProfileKey())))
                .thenReturn(List.of())
                .thenReturn(List.of(stored));
        when(jdbcTemplate.update(any(String.class), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        EmbeddingProfile profile = registry.initialize();

        assertEquals(7L, profile.id());
    }

    @Test
    void initializeRejectsStoredIdentityMismatch() {
        EmbeddingProfile mismatched = new EmbeddingProfile(
                7L, properties.getProfileKey(), "different-provider",
                properties.getModel(), properties.getModelRevision(),
                properties.getDimensions(), properties.getDistanceMetric(),
                properties.getNormalization(), true);
        stubQueryReturning(mismatched);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, registry::initialize);
        assertEquals("Embedding profile identity mismatch for provider: "
                + "configured=siliconflow, stored=different-provider", error.getMessage());
    }

    @Test
    void initializeRejectsDisabledProfile() {
        EmbeddingProfile disabled = new EmbeddingProfile(
                7L, properties.getProfileKey(), properties.getProvider(),
                properties.getModel(), properties.getModelRevision(),
                properties.getDimensions(), properties.getDistanceMetric(),
                properties.getNormalization(), false);
        stubQueryReturning(disabled);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, registry::initialize);
        assertEquals("Embedding profile is disabled: " + properties.getProfileKey(),
                error.getMessage());
    }

    @Test
    void initializeCachesActiveProfileAcrossCalls() {
        EmbeddingProfile stored = storedProfile();
        stubQueryReturning(stored);

        EmbeddingProfile first = registry.initialize();
        EmbeddingProfile second = registry.initialize();

        assertSame(first, second);
    }

    @Test
    void findRequiredByKeyThrowsForUnknownKey() {
        when(jdbcTemplate.query(eq(SELECT_SQL), any(RowMapper.class), eq("ghost")))
                .thenReturn(List.of());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> registry.findRequiredByKey("ghost"));
        assertEquals("Embedding profile not found: ghost", error.getMessage());
    }

    @Test
    void getActiveProfileLazilyInitializes() {
        EmbeddingProfile stored = storedProfile();
        stubQueryReturning(stored);

        EmbeddingProfile profile = registry.getActiveProfile();

        assertEquals(7L, profile.id());
    }
}
