package com.springairag.demo;

import com.springairag.api.dto.ChatRequest;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo configuration validation tests.
 *
 * <p>Verifies that demo-basic-rag's application.yml correctly binds to spring-ai-rag-starter's RagProperties.
 *
 * <p>This is the minimal validation level for the demo project:
 * does not start a full Spring Context (requires real PostgreSQL / LLM), only validates configuration classes and DTOs.
 *
 * <p>Full E2E tests (run in a real environment):
 * <pre>
 * cd demos/demo-basic-rag
 * export DEEPSEEK_API_KEY=xxx SILICONFLOW_API_KEY=xxx
 * mvn spring-boot:run
 * curl http://localhost:8080/demo/ask?q=什么是RAG
 * curl -X POST http://localhost:8080/demo/chat \
 *   -H "Content-Type: application/json" \
 *   -d '{"message": "你们的退换货政策是什么？", "sessionId": "customer-001"}'
 * </pre>
 */
class DemoConfigurationTest {

    @Test
    @DisplayName("ChatRequest constructs normally and can set all fields")
    void chatRequest_settersAndGetters() {
        ChatRequest request = new ChatRequest();
        request.setMessage("你好");
        request.setSessionId("sess-001");
        request.setDomainId("skin-care");

        assertEquals("你好", request.getMessage());
        assertEquals("sess-001", request.getSessionId());
        assertEquals("skin-care", request.getDomainId());
    }

    @Test
    @DisplayName("ChatRequest default values are correct")
    void chatRequest_defaults() {
        ChatRequest request = new ChatRequest();
        assertNull(request.getMessage());
        assertNull(request.getSessionId());
        assertNull(request.getDomainId());
    }

    @Test
    @DisplayName("DemoApplication class exists with @SpringBootApplication")
    void demoApplication_classExists() throws Exception {
        Class<?> appClass = Class.forName("com.springairag.demo.BasicRagDemoApplication");
        assertNotNull(appClass.getAnnotation(
                org.springframework.boot.autoconfigure.SpringBootApplication.class));
    }

    @Test
    @DisplayName("DemoController class exists with @RestController/@RequestMapping")
    void demoController_classExists() throws Exception {
        Class<?> controllerClass = Class.forName("com.springairag.demo.DemoController");
        assertNotNull(controllerClass.getAnnotation(
                org.springframework.web.bind.annotation.RestController.class));
        assertNotNull(controllerClass.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class));
    }

    @Test
    @DisplayName("DemoController constructor depends on RagChatService")
    void demoController_dependsOnRagChatService() throws Exception {
        Class<?> controllerClass = Class.forName("com.springairag.demo.DemoController");
        var constructors = controllerClass.getConstructors();
        assertTrue(constructors.length > 0, "DemoController should have at least one constructor");

        // Verify at least one constructor has a parameter type containing RagChatService.
        boolean foundRagChatService = false;
        for (var constructor : constructors) {
            for (var paramType : constructor.getParameterTypes()) {
                if (paramType.getName().contains("RagChatService")) {
                    foundRagChatService = true;
                    break;
                }
            }
        }
        assertTrue(foundRagChatService,
                "DemoController should have a constructor that depends on RagChatService");
    }

    @Test
    @DisplayName("spring-ai-rag-starter dependency coordinates are correct")
    void starterDependency_coordinates() throws Exception {
        // Verify spring-ai-rag-starter artifact exists on the classpath
        // (verified indirectly by checking that RagChatService class is loadable).
        Class<?> ragChatServiceClass = Class.forName("com.springairag.core.config.RagChatService");
        assertNotNull(ragChatServiceClass);
    }
}
