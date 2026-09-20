package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EmbeddingProfileRegistry 查找与初始化长尾（Batch 556，JaCoCo 驱
 * 动）：findRequiredByKey 命中/未抛、findByKey 行映射（lambda）、
 * initialize 后 getActiveProfile 惰性初始化、INSERT 后仍缺失 → 创
 * 建失败、禁用 Profile 拒绝。
 */
class EmbeddingProfileRegistryFindTailTest {

    private JdbcTemplate jdbcTemplate;
    private EmbeddingProfileRegistry registry;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        RagProperties properties = new RagProperties();
        registry = new EmbeddingProfileRegistry(jdbcTemplate, properties);
    }

    /** 触发 findByKey 的行映射 lambda：用 mock ResultSet 走一遍映射。 */
    @SuppressWarnings("unchecked")
    private void stubQueryReturningProfile(String profileKey,
                                           EmbeddingProfile profile) {
        when(jdbcTemplate.query(
                anyString(), any(RowMapper.class), eq(profileKey)))
                .thenAnswer(invocation -> {
                    RowMapper<EmbeddingProfile> mapper =
                            (RowMapper<EmbeddingProfile>) invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(9L);
                    when(rs.getString("profile_key")).thenReturn(profile.profileKey());
                    when(rs.getString("provider")).thenReturn(profile.provider());
                    when(rs.getString("model_name")).thenReturn(profile.modelName());
                    when(rs.getString("model_revision")).thenReturn(profile.modelRevision());
                    when(rs.getInt("dimensions")).thenReturn(profile.dimensions());
                    when(rs.getString("distance_metric")).thenReturn(profile.distanceMetric());
                    when(rs.getString("normalization")).thenReturn(profile.normalization());
                    when(rs.getBoolean("enabled")).thenReturn(profile.enabled());
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private EmbeddingProfile defaultProfile(boolean enabled) {
        return new EmbeddingProfile(
                9L,
                EmbeddingProfileRegistry.DEFAULT_PROFILE_KEY,
                "siliconflow",
                "BAAI/bge-m3",
                "unspecified",
                1024,
                "COSINE",
                "PROVIDER_DEFAULT",
                enabled);
    }

    @Test
    void findRequiredByKeyReturnsMappedProfile() {
        stubQueryReturningProfile(
                EmbeddingProfileRegistry.DEFAULT_PROFILE_KEY,
                defaultProfile(true));

        EmbeddingProfile profile =
                registry.findRequiredByKey(
                        EmbeddingProfileRegistry.DEFAULT_PROFILE_KEY);

        assertEquals(9L, profile.id());
        assertEquals("siliconflow", profile.provider());
        assertEquals(1024, profile.dimensions());
        assertTrue(profile.enabled());
    }

    @Test
    void findRequiredByKeyThrowsWhenMissing() {
        when(jdbcTemplate.query(
                anyString(), any(RowMapper.class), anyString()))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class,
                () -> registry.findRequiredByKey("ghost"));
    }

    @Test
    void initializeCachesActiveProfile() {
        stubQueryReturningProfile(
                EmbeddingProfileRegistry.DEFAULT_PROFILE_KEY,
                defaultProfile(true));

        EmbeddingProfile first = registry.getActiveProfile();
        EmbeddingProfile second = registry.getActiveProfile();

        assertSame(first, second);
    }

    @Test
    void initializeThrowsWhenProfileStillMissingAfterInsert() {
        when(jdbcTemplate.query(
                anyString(), any(RowMapper.class), anyString()))
                .thenReturn(List.of());

        var error = assertThrows(IllegalStateException.class,
                registry::getActiveProfile);

        assertTrue(error.getMessage().contains("Failed to create embedding profile"));
    }

    @Test
    void initializeRejectsDisabledProfile() {
        stubQueryReturningProfile(
                EmbeddingProfileRegistry.DEFAULT_PROFILE_KEY,
                defaultProfile(false));

        var error = assertThrows(IllegalStateException.class,
                registry::getActiveProfile);

        assertTrue(error.getMessage().contains("Embedding profile is disabled"));
    }
}
