package example.consumer;

import com.springairag.core.budget.BudgetReservationService;
import com.springairag.core.controller.DataGovernanceController;
import com.springairag.core.controller.OpenAiCompatibilityController;
import com.springairag.core.filter.RequestTraceFilter;
import com.springairag.core.metrics.RagHealthIndicator;
import com.springairag.core.service.DataGovernanceCleanupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 以不属于 com.springairag 的外部包模拟 Starter Consumer。
 *
 * <p>该应用只声明自己的 Spring Boot 入口，不做 Core 包扫描；Core Bean Graph
 * 必须由 Starter 自动配置提供。</p>
 */
@SpringBootTest(
        classes = ExternalStarterConsumerContextTest.ExternalConsumerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.web-application-type=none",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.flyway.enabled=true",
                "spring.ai.openai.api-key=test-key",
                "spring.ai.openai.base-url=http://127.0.0.1:9",
                "rag.embedding.api-key=test-key",
                "rag.embedding.base-url=http://127.0.0.1:9",
                "rag.openai-compatibility.enabled=true",
                "rag.cors.enabled=false",
                "rag.web-ui.enabled=false",
                "rag.embedding-jobs.enabled=false",
                "rag.document-processing.enabled=false",
                "rag.document-processing.housekeeping-enabled=false",
                "rag.integration-observability.enabled=false",
                "rag.data-governance.cleanup-enabled=true",
                "rag.data-governance.central-cleanup-enabled=true",
                "rag.llm-budget.enabled=false"
        })
@EnabledIfSystemProperty(named = "starter-consumer.it.enabled", matches = "true")
@Testcontainers
class ExternalStarterConsumerContextTest {

    private static final String POSTGRES_IMAGE_PROPERTY = "testcontainers.pg.image";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse(postgresImage()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("spring_ai_rag_starter_consumer_test")
            .withUsername("postgres")
            .withPassword("postgres");

    private static String postgresImage() {
        return System.getProperty(
                POSTGRES_IMAGE_PROPERTY,
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
    }

    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void externalConsumerReceivesCoreBeanGraphWithoutScanningCorePackage() {
        assertNotNull(context.getBean(OpenAiCompatibilityController.class));
        assertNotNull(context.getBean(BudgetReservationService.class));
        assertNotNull(context.getBean(DataGovernanceController.class));
        assertNotNull(context.getBean(DataGovernanceCleanupService.class));
        assertNotNull(context.getBean(RequestTraceFilter.class));
        assertNotNull(context.getBean(RagHealthIndicator.class));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ExternalConsumerApplication {
    }
}
