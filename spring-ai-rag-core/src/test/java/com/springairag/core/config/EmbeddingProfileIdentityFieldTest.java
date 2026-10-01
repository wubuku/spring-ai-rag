package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 嵌入 profile 身份校验的<strong>逐字段</strong>空白矩阵（Batch 766）。
 *
 * <p>{@code validateConfiguredIdentity} 用一条六段的 {@code ||} 链守住
 * provider / model / modelRevision / distanceMetric / normalization / profileKey
 * 六个身份字段。既有测试只把 {@code profileKey} 置空过一次，
 * 也就是说：另外五个字段各自为空白时，<strong>没有任何测试断言它会被拒绝</strong>。
 * 任何一段被误删，配置就能带着空白身份落库，之后所有向量都挂在一个无名 profile 上。
 *
 * <p>本类为每个字段各写"空白串"与"null"两种情况，因为 {@code isBlank} 同时
 * 接受二者——把 null 和 {@code " "} 当成同一条路径是常见的误解。
 */
class EmbeddingProfileIdentityFieldTest {

    private JdbcTemplate jdbcTemplate;
    private RagProperties ragProperties;
    private RagEmbeddingProperties properties;
    private EmbeddingProfileRegistry registry;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        ragProperties = new RagProperties();
        properties = ragProperties.getEmbedding();
        registry = new EmbeddingProfileRegistry(jdbcTemplate, ragProperties);
    }

    private void stubStoredProfileMatchingConfiguration() {
        EmbeddingProfile stored = new EmbeddingProfile(
                7L,
                properties.getProfileKey(),
                properties.getProvider(),
                properties.getModel(),
                properties.getModelRevision(),
                properties.getDimensions(),
                properties.getDistanceMetric(),
                properties.getNormalization(),
                true);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core
                .RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.List.of(stored));
    }

    private void assertBlankRejected(String description, Consumer<RagEmbeddingProperties> blank) {
        blank.accept(properties);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                registry::initialize, () -> description + " 时必须拒绝初始化");
        assertEquals("Embedding profile identity fields must not be blank",
                error.getMessage(), description);
    }

    // ==================== 逐字段：空白串 ====================

    @Test
    @DisplayName("profileKey 为空白串时拒绝")
    void blankProfileKeyIsRejected() {
        assertBlankRejected("profileKey", p -> p.setProfileKey("   "));
    }

    @Test
    @DisplayName("provider 为空白串时拒绝")
    void blankProviderIsRejected() {
        assertBlankRejected("provider", p -> p.setProvider("  "));
    }

    @Test
    @DisplayName("model 为空白串时拒绝")
    void blankModelIsRejected() {
        assertBlankRejected("model", p -> p.setModel("\t"));
    }

    @Test
    @DisplayName("modelRevision 为空白串时拒绝")
    void blankModelRevisionIsRejected() {
        assertBlankRejected("modelRevision", p -> p.setModelRevision(" "));
    }

    @Test
    @DisplayName("distanceMetric 为空白串时拒绝")
    void blankDistanceMetricIsRejected() {
        assertBlankRejected("distanceMetric", p -> p.setDistanceMetric("   "));
    }

    @Test
    @DisplayName("normalization 为空白串时拒绝")
    void blankNormalizationIsRejected() {
        assertBlankRejected("normalization", p -> p.setNormalization("  "));
    }

    // ==================== 逐字段：null ====================

    @Test
    @DisplayName("profileKey 为 null 时拒绝")
    void nullProfileKeyIsRejected() {
        assertBlankRejected("profileKey=null", p -> p.setProfileKey(null));
    }

    @Test
    @DisplayName("provider 为 null 时拒绝")
    void nullProviderIsRejected() {
        assertBlankRejected("provider=null", p -> p.setProvider(null));
    }

    @Test
    @DisplayName("model 为 null 时拒绝")
    void nullModelIsRejected() {
        assertBlankRejected("model=null", p -> p.setModel(null));
    }

    @Test
    @DisplayName("modelRevision 为 null 时拒绝")
    void nullModelRevisionIsRejected() {
        assertBlankRejected("modelRevision=null", p -> p.setModelRevision(null));
    }

    @Test
    @DisplayName("distanceMetric 为 null 时拒绝")
    void nullDistanceMetricIsRejected() {
        assertBlankRejected("distanceMetric=null", p -> p.setDistanceMetric(null));
    }

    @Test
    @DisplayName("normalization 为 null 时拒绝")
    void nullNormalizationIsRejected() {
        assertBlankRejected("normalization=null", p -> p.setNormalization(null));
    }

    // ==================== 仍然有效的相邻校验 ====================

    @ParameterizedTest(name = "distanceMetric={0} 被拒")
    @ValueSource(strings = {"L2", "EUCLIDEAN", "cosine", "COSINE ", " IP"})
    @DisplayName("非精确 COSINE 的距离度量一律拒绝")
    void onlyExactCosineIsAccepted(String metric) {
        properties.setDistanceMetric(metric);
        properties.setProfileKey("custom-key");

        // 大小写与首尾空格都必须拒绝：距离度量一旦被归一化，
        // 已有向量与新查询向量就处在不同度量下，相似度结果没有意义。
        IllegalStateException error = assertThrows(
                IllegalStateException.class, registry::initialize);
        assertEquals("Only COSINE embedding distance is supported in this release",
                error.getMessage());
    }

    @Test
    @DisplayName("六个身份字段都有效且匹配内置身份时初始化成功")
    void validBuiltInIdentityInitializes() {
        stubStoredProfileMatchingConfiguration();

        EmbeddingProfile profile = assertDoesNotThrow(registry::initialize);

        assertEquals(EmbeddingProfileRegistry.DEFAULT_PROFILE_KEY,
                profile.profileKey());
        assertEquals(1024, profile.dimensions());
    }

    @Test
    @DisplayName("内置 profileKey 不得搭配被改写的身份")
    void builtInProfileKeyRejectsOverriddenIdentity() {
        // profileKey 保持内置值，但把 normalization 改成别的值——
        // 这会让"内置键"指向一个非内置身份的向量，污染既有集合。
        properties.setNormalization("L2_NORMALIZED");

        IllegalStateException error = assertThrows(
                IllegalStateException.class, registry::initialize);

        assertEquals(
                "RAG_EMBEDDING_PROFILE_KEY must be explicit when overriding embedding identity",
                error.getMessage());
    }

    @Test
    @DisplayName("内置 profileKey 搭配被改写的维度同样拒绝")
    void builtInProfileKeyRejectsOverriddenDimensions() {
        properties.setDimensions(768);

        assertThrows(IllegalStateException.class, registry::initialize);
    }

    @Test
    @DisplayName("显式自定义 profileKey 允许覆盖身份")
    void customProfileKeyAllowsOverriddenIdentity() {
        properties.setProfileKey("custom-key");
        properties.setProvider("other-provider");
        stubStoredProfileMatchingConfiguration();

        assertDoesNotThrow(registry::initialize);
    }
}
