package com.springairag.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打包镜像的真实配置文件是 demo 的 {@code application.yml}；fat jar 中
 * {@code BOOT-INF/classes} 优先于依赖 jar，因此 core 的同名文件会被整体遮蔽。
 *
 * <p>本测试用 demo 自己的 {@code application.yml} 加载配置，并注入模拟的
 * 进程环境变量，断言文档承诺的变量确实解析到对应属性。它专门防两类回归：
 * <ul>
 *   <li>删掉 {@code ${POSTGRES_*}} / {@code ${RAG_ROOT_API_KEY}} 占位符后，
 *       变量名与属性路径不一致的项会静默失效；</li>
 *   <li>把属性写成硬编码值，环境变量不再生效。</li>
 * </ul>
 */
class ApplicationEnvironmentBindingTest {

    private static final String ROOT_KEY = "binding-test-root-key-0123456789";

    @Test
    void environmentVariablesResolveToDocumentedProperties() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "test-env",
                Map.of(
                        // 安全 root key 的变量名与属性路径不一致，只能通过占位符映射。
                        "RAG_ROOT_API_KEY", ROOT_KEY,
                        "RAG_SECURITY_ENABLED", "true",
                        // 数据库变量名同样与 spring.datasource.* 不一致。
                        "POSTGRES_HOST", "db.binding.test",
                        "POSTGRES_PORT", "5442",
                        "POSTGRES_DATABASE", "rag_binding_test",
                        "POSTGRES_USER", "rag_binding_user",
                        "POSTGRES_PASSWORD", "rag_binding_password",
                        // embedding profile 走 relaxed 绑定，但占位符缺失时默认值会丢。
                        "RAG_EMBEDDING_PROFILE_KEY", "custom-bge-m3-1024-v2",
                        "RAG_EMBEDDING_DIMENSIONS", "1024",
                        "RAG_EMBEDDING_MODEL", "BAAI/bge-m3-custom")));

        List<PropertySource<?>> yamlSources = new YamlPropertySourceLoader()
                .load("demo-application", new ClassPathResource("application.yml"));
        yamlSources.forEach(environment.getPropertySources()::addLast);

        // 1) 安全 root key 与认证开关
        assertEquals(ROOT_KEY, environment.getProperty("rag.security.root-api-key"),
                "RAG_ROOT_API_KEY 必须解析到 rag.security.root-api-key");
        assertEquals("true", environment.getProperty("rag.security.enabled"),
                "RAG_SECURITY_ENABLED 必须解析到 rag.security.enabled");

        // 2) 数据源
        String datasourceUrl = environment.getProperty("spring.datasource.url");
        assertNotNull(datasourceUrl, "spring.datasource.url 必须存在");
        assertTrue(datasourceUrl.contains("db.binding.test:5442/rag_binding_test"),
                "POSTGRES_* 必须解析进 JDBC URL，实际: " + datasourceUrl);
        assertEquals("rag_binding_user", environment.getProperty("spring.datasource.username"),
                "POSTGRES_USER 必须解析到数据源用户名");
        assertEquals("rag_binding_password", environment.getProperty("spring.datasource.password"),
                "POSTGRES_PASSWORD 必须解析到数据源口令");

        // 3) Embedding profile 与模型维度
        assertEquals("custom-bge-m3-1024-v2",
                environment.getProperty("rag.embedding.profile-key"),
                "RAG_EMBEDDING_PROFILE_KEY 必须解析到 rag.embedding.profile-key");
        assertEquals("BAAI/bge-m3-custom", environment.getProperty("rag.embedding.model"),
                "RAG_EMBEDDING_MODEL 必须解析到 rag.embedding.model");
        assertEquals("1024", environment.getProperty("rag.embedding.dimensions"),
                "RAG_EMBEDDING_DIMENSIONS 必须解析到 rag.embedding.dimensions");
    }
}
