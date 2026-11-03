package com.springairag.demo;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * demo 模块的 {@code application.yml} 会整体遮蔽 core jar 中的同名文件
 * （fat jar 中 {@code BOOT-INF/classes} 优先于依赖 jar）。
 *
 * <p>本测试遍历所有 demo 的 {@code application.yml}，注入模拟的进程环境变量，
 * 断言文档承诺的变量确实解析到对应属性。它专门防两类回归：
 * <ul>
 *   <li>删掉 {@code ${POSTGRES_*}} / {@code ${RAG_ROOT_API_KEY}} 占位符后，
 *       变量名与属性路径不一致的项会静默失效；</li>
 *   <li>把属性写成硬编码值，环境变量不再生效。</li>
 * </ul>
 *
 * <p>覆盖安全 root key、数据源、embedding profile 三类关键配置。
 */
class ApplicationEnvironmentBindingTest {

    private static final String ROOT_KEY = "binding-test-root-key-0123456789";

    static Stream<Arguments> demoApplicationConfigs() {
        return Stream.of(
                Arguments.of("demo-basic-rag",
                        Path.of("src/main/resources/application.yml")),
                Arguments.of("demo-multi-model",
                        Path.of("../demo-multi-model/src/main/resources/application.yml")),
                Arguments.of("demo-component-level",
                        Path.of("../demo-component-level/src/main/resources/application.yml")),
                Arguments.of("demo-domain-extension",
                        Path.of("../demo-domain-extension/src/main/resources/application.yml")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("demoApplicationConfigs")
    void environmentVariablesResolveToDocumentedProperties(String demo, Path configFile)
            throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "test-env",
                Map.ofEntries(
                        // 安全 root key 的变量名与属性路径不一致，只能通过占位符映射。
                        Map.entry("RAG_ROOT_API_KEY", ROOT_KEY),
                        Map.entry("RAG_SECURITY_ENABLED", "true"),
                        // 数据库变量名同样与 spring.datasource.* 不一致。
                        Map.entry("POSTGRES_HOST", "db.binding.test"),
                        Map.entry("POSTGRES_PORT", "5442"),
                        Map.entry("POSTGRES_DATABASE", "rag_binding_test"),
                        Map.entry("POSTGRES_USER", "rag_binding_user"),
                        Map.entry("POSTGRES_PASSWORD", "rag_binding_password"),
                        // 连接池调优变量名与 spring.datasource.hikari.* 不一致。
                        Map.entry("DB_POOL_SIZE", "7"),
                        Map.entry("DB_POOL_MIN_IDLE", "3"),
                        // embedding profile 走 relaxed 绑定，但占位符缺失时默认值会丢。
                        Map.entry("RAG_EMBEDDING_PROFILE_KEY", "custom-bge-m3-1024-v2"),
                        Map.entry("RAG_EMBEDDING_DIMENSIONS", "1024"),
                        Map.entry("RAG_EMBEDDING_MODEL", "BAAI/bge-m3-custom"))));

        List<PropertySource<?>> yamlSources = new YamlPropertySourceLoader()
                .load(demo, new FileSystemResource(configFile));
        yamlSources.forEach(environment.getPropertySources()::addLast);

        // 1) 安全 root key 与认证开关
        assertEquals(ROOT_KEY, environment.getProperty("rag.security.root-api-key"),
                demo + ": RAG_ROOT_API_KEY 必须解析到 rag.security.root-api-key");
        assertEquals("true", environment.getProperty("rag.security.enabled"),
                demo + ": RAG_SECURITY_ENABLED 必须解析到 rag.security.enabled");

        // 2) 数据源
        String datasourceUrl = environment.getProperty("spring.datasource.url");
        assertNotNull(datasourceUrl, demo + ": spring.datasource.url 必须存在");
        assertTrue(datasourceUrl.contains("db.binding.test:5442/rag_binding_test"),
                demo + ": POSTGRES_* 必须解析进 JDBC URL，实际: " + datasourceUrl);
        assertEquals("rag_binding_user", environment.getProperty("spring.datasource.username"),
                demo + ": POSTGRES_USER 必须解析到数据源用户名");
        assertEquals("rag_binding_password", environment.getProperty("spring.datasource.password"),
                demo + ": POSTGRES_PASSWORD 必须解析到数据源口令");
        assertEquals("7", environment.getProperty("spring.datasource.hikari.maximum-pool-size"),
                demo + ": DB_POOL_SIZE 必须解析到连接池上限");
        assertEquals("3", environment.getProperty("spring.datasource.hikari.minimum-idle"),
                demo + ": DB_POOL_MIN_IDLE 必须解析到连接池空闲下限");

        // 3) Embedding profile 与模型维度
        assertEquals("custom-bge-m3-1024-v2",
                environment.getProperty("rag.embedding.profile-key"),
                demo + ": RAG_EMBEDDING_PROFILE_KEY 必须解析到 rag.embedding.profile-key");
        assertEquals("BAAI/bge-m3-custom", environment.getProperty("rag.embedding.model"),
                demo + ": RAG_EMBEDDING_MODEL 必须解析到 rag.embedding.model");
        assertEquals("1024", environment.getProperty("rag.embedding.dimensions"),
                demo + ": RAG_EMBEDDING_DIMENSIONS 必须解析到 rag.embedding.dimensions");
    }
}
