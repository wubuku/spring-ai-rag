package com.springairag.core.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.ApiKeyProvisioningOperationRepository;
import com.springairag.core.repository.ApiKeyRotationOperationRepository;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.service.ApiKeyManagementService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 0 + Phase 2：STRICT 模式运营方控制面闭环 HTTP 集成测试（真实 PostgreSQL/Testcontainers）。
 *
 * <p>显式 opt-in：{@code -Doperator-http.it.enabled=true}。覆盖 P0-2 开户/发 Key 闭环、
 * P0-3 suspend 后边缘 403、P0-4 default 保护与 lifecycle-actors 放通、V86 审计与配额落库。</p>
 */
@EnabledIfSystemProperty(named = "operator-http.it.enabled", matches = "true")
@SpringBootTest(
        classes = OperatorConsoleStrictHttpPostgresIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Operator console STRICT HTTP lifecycle (real PostgreSQL)")
class OperatorConsoleStrictHttpPostgresIntegrationTest {

    private static final String ROOT_KEY = "operator-http-integration-root-key-2026-11";
    private static final String OPERATOR_KEY = "rag_sk_operator_http_integration_2026_11";
    private static final String OPERATOR_ID = "platform-ops-bot";

    private static PostgreSQLContainer<?> postgres;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void requireFlag() {
        assumeTrue(Boolean.getBoolean("operator-http.it.enabled"),
                "Set -Doperator-http.it.enabled=true to run operator HTTP integration test");
    }

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        String image = System.getProperty("testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_operator_http_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        PGSimpleDataSource migrationDs = new PGSimpleDataSource();
        migrationDs.setURL(postgres.getJdbcUrl());
        migrationDs.setUser("postgres");
        migrationDs.setPassword("postgres");
        Flyway.configure().dataSource(migrationDs)
                .locations("classpath:db/migration").baselineOnMigrate(true).load().migrate();

        JdbcTemplate admin = new JdbcTemplate(migrationDs);
        admin.execute("CREATE ROLE rag_app LOGIN PASSWORD 'rag_app' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA public TO rag_app");
        admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rag_app");
        admin.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO rag_app");

        PGSimpleDataSource appDs = new PGSimpleDataSource();
        appDs.setURL(postgres.getJdbcUrl());
        appDs.setUser("rag_app");
        appDs.setPassword("rag_app");
        TestApplication.dataSource = appDs;

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "rag_app");
        registry.add("spring.datasource.password", () -> "rag_app");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("P0-2/P0-3/P0-4/V86：STRICT 运营方 HTTP 全链路闭环")
    void strictOperatorHttpLifecycle() throws Exception {
        seedOperatorCredential();

        JsonNode t1 = createTenant("t1", "Tenant One");
        JsonNode t2 = createTenant("t2", "Tenant Two");
        String t1Key = t1.at("/bootstrapKey/rawKeyOnce").asText();
        String t2Key = t2.at("/bootstrapKey/rawKeyOnce").asText();
        assertFalse(t1Key.isBlank());
        assertFalse(t2Key.isBlank());
        assertFalse(t1Key.equals(t2Key));

        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.GET,
                "/api/v1/rag/operator/tenants", t1Key, null).getStatusCode());
        assertEquals(HttpStatus.OK, request(HttpMethod.GET,
                "/api/v1/rag/operator/tenants", OPERATOR_KEY, null).getStatusCode());

        ResponseEntity<String> defaultDisable = request(HttpMethod.POST,
                "/api/v1/rag/operator/tenants/default/disable", ROOT_KEY,
                "{\"reason\":\"protect default\"}");
        assertEquals(HttpStatus.CONFLICT, defaultDisable.getStatusCode());
        assertTrue(defaultDisable.getBody().contains("DEFAULT_TENANT_PROTECTED"));
        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.DELETE,
                "/api/v1/rag/operator/tenants/default", ROOT_KEY,
                "{\"reason\":\"protect default\"}").getStatusCode());

        // P0-2：认证投影绑定各自租户；RLS 隔离用真实 app 角色 + GUC 在数据库层验证。
        ResponseEntity<String> t1Identity = request(HttpMethod.GET,
                "/api/v1/rag/auth/me", t1Key, null);
        ResponseEntity<String> t2Identity = request(HttpMethod.GET,
                "/api/v1/rag/auth/me", t2Key, null);
        assertEquals(HttpStatus.OK, t1Identity.getStatusCode());
        assertEquals(HttpStatus.OK, t2Identity.getStatusCode());
        assertTrue(t1Identity.getBody().contains("t1"));
        assertTrue(t2Identity.getBody().contains("t2"));

        insertCollection("t1", "it-t1-collection");
        insertCollection("t2", "it-t2-collection");
        assertEquals(1, countCollectionsInScope("t1"));
        assertEquals(1, countCollectionsInScope("t2"));
        assertEquals(0, countCollectionsInScope("t1", "it-t2-collection"));
        assertEquals(0, countCollectionsInScope("t2", "it-t1-collection"));

        assertEquals(HttpStatus.OK, request(HttpMethod.POST,
                "/api/v1/rag/operator/tenants/t1/suspend", ROOT_KEY,
                "{\"reason\":\"integration suspend\"}").getStatusCode());
        ResponseEntity<String> collectionsDenied = request(HttpMethod.GET,
                "/api/v1/rag/auth/me", t1Key, null);
        assertEquals(HttpStatus.FORBIDDEN, collectionsDenied.getStatusCode());
        assertTrue(collectionsDenied.getBody().contains("TENANT_SUSPENDED"));
        ResponseEntity<String> openAiDenied = request(HttpMethod.POST,
                "/v1/chat/completions", t1Key,
                "{\"model\":\"rag-default\",\"messages\":[]}");
        assertEquals(HttpStatus.FORBIDDEN, openAiDenied.getStatusCode());
        assertTrue(openAiDenied.getBody().contains("tenant_suspended"));

        assertEquals(HttpStatus.OK, request(HttpMethod.POST,
                "/api/v1/rag/operator/tenants/t1/resume", ROOT_KEY,
                "{\"reason\":\"integration resume\"}").getStatusCode());
        // resume 后通过认证投影与租户状态断言解禁（data-plane controller 由专用切片覆盖）。
        ResponseEntity<String> identity = request(HttpMethod.GET,
                "/api/v1/rag/auth/me", t1Key, null);
        assertEquals(HttpStatus.OK, identity.getStatusCode());
        assertTrue(identity.getBody().contains("t1"));

        assertEquals(HttpStatus.OK, request(HttpMethod.PUT,
                "/api/v1/rag/operator/tenants/t1/quotas/MONTH", ROOT_KEY,
                "{\"enabled\":true,\"maxTokens\":123456,\"reason\":\"integration quota\"}")
                .getStatusCode());
        ResponseEntity<String> quota = request(HttpMethod.PUT,
                "/api/v1/rag/operator/tenants/t1/quotas/DAY", ROOT_KEY,
                "{\"enabled\":true,\"maxTokens\":12345,\"reason\":\"integration quota\"}");
        assertEquals(HttpStatus.OK, quota.getStatusCode(), quota.getBody());
        assertEquals(12345L, jdbc.queryForObject(
                "SELECT daily_token_limit FROM rag_tenant_quota_policy WHERE tenant_id='t1'",
                Long.class).longValue());

        JsonNode audits = objectMapper.readTree(request(HttpMethod.GET,
                "/api/v1/rag/operator/audit-events?limit=100", ROOT_KEY, null).getBody());
        assertTrue(audits.size() >= 6);
        long previous = Long.MAX_VALUE;
        boolean quotaSeen = false;
        boolean suspendSeen = false;
        for (JsonNode event : audits) {
            long seq = event.path("seq").asLong();
            assertTrue(seq < previous, "审计必须按 seq DESC 返回");
            previous = seq;
            quotaSeen |= "QUOTA_UPDATE".equals(event.path("action").asText());
            suspendSeen |= "TENANT_SUSPEND".equals(event.path("action").asText());
        }
        assertTrue(quotaSeen);
        assertTrue(suspendSeen);
    }

    private JsonNode createTenant(String tenantId, String displayName) throws Exception {
        ResponseEntity<String> response = request(HttpMethod.POST,
                "/api/v1/rag/operator/tenants", ROOT_KEY,
                "{\"tenantId\":\"" + tenantId + "\",\"displayName\":\"" + displayName
                        + "\",\"issueBootstrapKey\":true,\"reason\":\"integration create\"}");
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("ACTIVE", body.at("/tenant/status").asText());
        return body;
    }

    private void seedOperatorCredential() {
        if (jdbc.queryForObject("SELECT count(*) FROM rag_api_principal WHERE principal_id=?",
                Integer.class, OPERATOR_ID) > 0) {
            return;
        }
        jdbc.update("INSERT INTO rag_api_principal "
                        + "(principal_id, name, role, capabilities, policy_version, "
                        + " next_credential_version, created_at, updated_at, tenant_id) "
                        + "VALUES (?, ?, 'ADMIN', 'RAG_READ,RAG_WRITE', 1, 2, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'default')",
                OPERATOR_ID, "operator integration");
        jdbc.update("INSERT INTO rag_api_key "
                        + "(key_id, key_hash, principal_id, credential_version, name, enabled, "
                        + "role, created_at, tenant_id) "
                        + "VALUES (?, ?, ?, 1, ?, TRUE, 'ADMIN', CURRENT_TIMESTAMP, 'default')",
                "rag_k_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                sha256(OPERATOR_KEY), OPERATOR_ID, "operator integration");
    }

    private void insertCollection(String tenantId, String collectionKey) {
        // 测试夹具：用管理员连接预置租户私有行；HTTP 读取仍走业务 Key 的 RLS 隔离。
        PGSimpleDataSource adminDs = new PGSimpleDataSource();
        adminDs.setURL(postgres.getJdbcUrl());
        adminDs.setUser("postgres");
        adminDs.setPassword("postgres");
        new JdbcTemplate(adminDs).update(
                "INSERT INTO rag_collection (collection_key, name, tenant_id) VALUES (?, ?, ?)",
                collectionKey, collectionKey, tenantId);
    }

    private long countCollectionsInScope(String tenantId) {
        return countCollectionsInScope(tenantId, null);
    }

    private long countCollectionsInScope(String tenantId, String collectionKey) {
        PGSimpleDataSource appDs = new PGSimpleDataSource();
        appDs.setURL(postgres.getJdbcUrl());
        appDs.setUser("rag_app");
        appDs.setPassword("rag_app");
        try (java.sql.Connection c = appDs.getConnection()) {
            try (java.sql.PreparedStatement guc = c.prepareStatement(
                    "SELECT set_config('app.current_tenant_id', ?, false)")) {
                guc.setString(1, tenantId);
                guc.execute();
            }
            String sql = collectionKey == null
                    ? "SELECT count(*) FROM rag_collection"
                    : "SELECT count(*) FROM rag_collection WHERE collection_key = ?";
            try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
                if (collectionKey != null) {
                    ps.setString(1, collectionKey);
                }
                var rs = ps.executeQuery();
                rs.next();
                return rs.getLong(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> request(HttpMethod method, String path,
                                           String apiKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", apiKey == null ? "" : apiKey);
        return http.exchange(org.springframework.web.util.UriComponentsBuilder
                        .fromHttpUrl("http://127.0.0.1:" + port + path).build().toUri(),
                method, new HttpEntity<>(body == null ? "" : body, headers), String.class);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.boot.autoconfigure.domain.EntityScan(
            basePackageClasses = com.springairag.core.entity.RagCollection.class)
    @EnableJpaRepositories(basePackageClasses = {
            RagApiKeyRepository.class, RagApiPrincipalRepository.class})
    @EnableTransactionManagement
    @Import({
            com.springairag.core.controller.OpenAiCompatibilityController.class,
            com.springairag.core.controller.GlobalExceptionHandler.class,
            com.springairag.core.filter.ApiCapabilityFilter.class,
            com.springairag.core.controller.ApiKeyIdentityController.class
    })
    static class TestApplication {
        static DataSource dataSource;

        @Bean(name = "dataSource")
        DataSource dataSource() {
            if (dataSource == null) {
                PGSimpleDataSource ds = new PGSimpleDataSource();
                ds.setURL("jdbc:postgresql://localhost:5432/postgres");
                ds.setUser("postgres");
                ds.setPassword("postgres");
                return ds;
            }
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            LocalContainerEntityManagerFactoryBean factory =
                    new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.springairag.core.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "none",
                    "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(
                LocalContainerEntityManagerFactoryBean factory) {
            return new JpaTransactionManager(factory.getObject());
        }

        @Bean
        RagProperties ragProperties() {
            RagProperties properties = new RagProperties();
            // 启动期 RLS 守卫由 TenantRlsVerifierPostgresIntegrationTest 单独覆盖。
            // 这里用 SINGLE resolver 模式，鉴权/租户过滤器仍按 STRICT 语义装配，
            // 避免启动期对平台角色的额外约束；业务断言仍验证非 ACTIVE 403。
            properties.getTenant().setMode(TenantMode.SINGLE);
            properties.getTenant().setLifecycleActors(
                    List.of("environment-root", OPERATOR_ID));
            properties.getOpenAiCompatibility().setEnabled(true);
            return properties;
        }

        @Bean(name = "tenantStartupGuardRunner")
        org.springframework.boot.ApplicationRunner tenantStartupGuardRunner() {
            return args -> {
                // 启动期 RLS/模式守卫已在专用集成测试覆盖；本切片跳过，避免与
                // 平台角色权限矩阵耦合（见类注释）。
            };
        }

        @Bean
        com.springairag.core.security.EnvironmentRootCredentialResolver rootResolver() {
            return new com.springairag.core.security.EnvironmentRootCredentialResolver(ROOT_KEY);
        }

        @Bean
        ApiKeyManagementService apiKeyManagementService(
                RagApiKeyRepository keys, RagApiPrincipalRepository principals,
                JdbcTemplate jdbc, ApiKeyProvisioningOperationRepository provisioning,
                ApiKeyRotationOperationRepository rotation, RagProperties properties,
                PlatformTransactionManager tx) {
            return new ApiKeyManagementService(keys, principals, null, jdbc, provisioning,
                    rotation, properties, tx);
        }

        @Bean
        OperatorConsoleService operatorConsoleService(
                JdbcTemplate jdbc,
                ApiKeyManagementService apiKeys,
                RagProperties properties) {
            TransactionTemplate tx = new TransactionTemplate(
                    new DataSourceTransactionManager(jdbc.getDataSource()));
            TenantLifecycleService lifecycle = new TenantLifecycleService(
                    jdbc, tx, TenantMode.STRICT, new JdbcTenantLifecycleAudit(jdbc));
            PlatformCapabilityRegistry registry = new PlatformCapabilityRegistry();
            registry.allowActor("environment-root");
            registry.allowActor(OPERATOR_ID);
            registry.register(PlatformCapability.TENANT_LIFECYCLE, lifecycle::describe);
            return new OperatorConsoleService(
                    jdbc, lifecycle, apiKeys, new TenantQuotaPolicyRepository(jdbc),
                    new JdbcTenantLifecycleAudit(jdbc), TenantMode.STRICT, null);
        }

        @Bean
        TenantLifecycleService tenantLifecycleService(JdbcTemplate jdbc) {
            return new TenantLifecycleService(jdbc,
                    new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                    TenantMode.STRICT, new JdbcTenantLifecycleAudit(jdbc));
        }

        @Bean
        PlatformCapabilityRegistry platformCapabilityRegistry(TenantLifecycleService lifecycle) {
            PlatformCapabilityRegistry registry = new PlatformCapabilityRegistry();
            registry.allowActor("environment-root");
            registry.allowActor(OPERATOR_ID);
            registry.register(PlatformCapability.TENANT_LIFECYCLE, lifecycle::describe);
            return registry;
        }

        @Bean
        OperatorConsoleController operatorConsoleController(
                OperatorConsoleService service,
                PlatformCapabilityRegistry registry,
                com.springairag.core.security.EnvironmentRootCredentialResolver root) {
            return new OperatorConsoleController(service, registry, root);
        }

        @Bean
        TenantLifecycleController tenantLifecycleController(
                TenantLifecycleService service,
                PlatformCapabilityRegistry registry,
                com.springairag.core.security.EnvironmentRootCredentialResolver root) {
            return new TenantLifecycleController(service, registry, root);
        }

        @Bean
        com.springairag.core.service.CollectionIdentityResolver collectionIdentityResolver(
                com.springairag.core.repository.RagCollectionRepository collections) {
            return new com.springairag.core.service.CollectionIdentityResolver(collections);
        }

        @Bean
        FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(
                ApiKeyManagementService service,
                com.springairag.core.security.EnvironmentRootCredentialResolver root) {
            FilterRegistrationBean<ApiKeyAuthFilter> registration =
                    new FilterRegistrationBean<>(new ApiKeyAuthFilter("", true, service, root));
            registration.addUrlPatterns("/api/*", "/v1/*");
            registration.setOrder(-10);
            return registration;
        }

        /** 真实 STRICT 模式租户过滤器：验证 suspend/disable 边缘 403。 */
        @Bean
        FilterRegistrationBean<com.springairag.core.tenant.TenantContextFilter>
                tenantContextFilterRegistration() {
            FilterRegistrationBean<com.springairag.core.tenant.TenantContextFilter> registration =
                    new FilterRegistrationBean<>(new com.springairag.core.tenant.TenantContextFilter(
                            new PrincipalTenantResolver(), TenantMode.STRICT));
            registration.addUrlPatterns("/api/*", "/v1/*");
            registration.setOrder(-8);
            return registration;
        }
    }
}
