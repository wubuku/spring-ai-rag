package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CorsConfig Unit Tests
 */
class CorsConfigTest {

    @Test
    void corsProperties_defaults() {
        RagCorsProperties cors = new RagCorsProperties();

        assertFalse(cors.isEnabled());
        assertEquals(List.of("*"), cors.getAllowedOrigins());
        assertEquals("GET,POST,PUT,DELETE,OPTIONS", cors.getAllowedMethods());
        assertEquals("*", cors.getAllowedHeaders());
        assertEquals(3600, cors.getMaxAge());
    }

    @Test
    void corsProperties_settersWork() {
        RagCorsProperties cors = new RagCorsProperties();
        cors.setEnabled(true);
        cors.setAllowedOrigins(List.of("https://example.com", "http://localhost:3000"));
        cors.setAllowedMethods("GET,POST");
        cors.setAllowedHeaders("Content-Type,Authorization");
        cors.setMaxAge(7200);

        assertTrue(cors.isEnabled());
        assertEquals(2, cors.getAllowedOrigins().size());
        assertEquals("https://example.com", cors.getAllowedOrigins().get(0));
        assertEquals("GET,POST", cors.getAllowedMethods());
        assertEquals("Content-Type,Authorization", cors.getAllowedHeaders());
        assertEquals(7200, cors.getMaxAge());
    }

    @Test
    void corsProperties_inRagProperties() {
        RagProperties props = new RagProperties();
        assertNotNull(props.getCors());
        assertFalse(props.getCors().isEnabled());
    }

    @Test
    void corsConfig_usesProperties() {
        RagProperties props = new RagProperties();
        props.getCors().setEnabled(true);
        props.getCors().setAllowedOrigins(List.of("https://example.com"));
        props.getCors().setAllowedMethods("GET,POST");
        props.getCors().setAllowedHeaders("Content-Type,Authorization");
        props.getCors().setMaxAge(7200);

        CorsConfig config = new CorsConfig(props);
        // Batch 956：原来只有 assertNotNull(config)——连 addCorsMappings
        // 都没调过，"usesProperties" 三个字没有任何东西支撑。名字、路径、
        // 允许的方法、maxAge 全部写错，这一条照样绿。
        ReadableCorsRegistry registry = new ReadableCorsRegistry();
        config.addCorsMappings(registry);

        Map<String, CorsConfiguration> mappings =
                registry.applied();
        assertEquals(Set.of("/api/**", "/v1/**"), mappings.keySet(),
                "两条路径映射都要注册");
        for (String pattern : List.of("/api/**", "/v1/**")) {
            CorsConfiguration cors = mappings.get(pattern);
            assertEquals(List.of("https://example.com"), cors.getAllowedOrigins(),
                    pattern + " 的允许来源应来自配置");
            assertEquals(List.of("GET", "POST"), cors.getAllowedMethods(),
                    pattern + " 的允许方法应按逗号切分");
            assertEquals(List.of("Content-Type", "Authorization"),
                    cors.getAllowedHeaders(), pattern);
            assertEquals(7200L, cors.getMaxAge(), pattern);
        }
    }

    @Test
    void corsConfig_registersConfiguredPathsWithWildcardDefault() {
        RagProperties props = new RagProperties();
        props.getCors().setEnabled(false);

        ReadableCorsRegistry registry = new ReadableCorsRegistry();
        new CorsConfig(props).addCorsMappings(registry);

        Map<String, CorsConfiguration> mappings =
                registry.applied();
        // 关闭 CORS 也要把映射注册上去（开关在别处生效），默认值是通配来源。
        // 断具体值而不是断非空：把默认来源从 "*" 改掉，这条要跟着红。
        CorsConfiguration cors = mappings.get("/api/**");
        assertNotNull(cors, "未注册 /api/**：" + mappings.keySet());
        assertEquals(List.of("*"), cors.getAllowedOrigins());
        assertEquals(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"),
                cors.getAllowedMethods(),
                "默认方法表应来自 RagCorsProperties");
    }

    /**
     * {@code CorsRegistry#getCorsConfigurations()} 是 protected，
     * 这里用子类把它暴露出来读。只是测试侧的读出口，
     * 没给生产代码加任何测试钩子。
     */
    private static final class ReadableCorsRegistry extends CorsRegistry {
        private Map<String, CorsConfiguration> applied() {
            return getCorsConfigurations();
        }
    }
}
