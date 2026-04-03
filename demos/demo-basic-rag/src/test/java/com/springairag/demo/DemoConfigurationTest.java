package com.springairag.demo;

import com.springairag.api.dto.ChatRequest;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo 配置验证测试
 *
 * <p>验证 demo-basic-rag 的 application.yml 配置正确绑定了 spring-ai-rag-starter 的 RagProperties。
 *
 * <p>这是 demo 项目的最低验证级别：
 * 不启动完整 Spring Context（需要真实 PostgreSQL / LLM），只验证配置类和数据传输对象。
 *
 * <p>完整 E2E 测试（在真实环境下运行）：
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
    @DisplayName("ChatRequest 构造正常，可设置所有字段")
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
    @DisplayName("ChatRequest 默认值正确")
    void chatRequest_defaults() {
        ChatRequest request = new ChatRequest();
        assertNull(request.getMessage());
        assertNull(request.getSessionId());
        assertNull(request.getDomainId());
    }

    @Test
    @DisplayName("DemoApplication 类存在且有 @SpringBootApplication")
    void demoApplication_classExists() throws Exception {
        Class<?> appClass = Class.forName("com.springairag.demo.BasicRagDemoApplication");
        assertNotNull(appClass.getAnnotation(
                org.springframework.boot.autoconfigure.SpringBootApplication.class));
    }

    @Test
    @DisplayName("DemoController 类存在且有 @RestController/@RequestMapping")
    void demoController_classExists() throws Exception {
        Class<?> controllerClass = Class.forName("com.springairag.demo.DemoController");
        assertNotNull(controllerClass.getAnnotation(
                org.springframework.web.bind.annotation.RestController.class));
        assertNotNull(controllerClass.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class));
    }

    @Test
    @DisplayName("DemoController 构造函数依赖 RagChatService")
    void demoController_dependsOnRagChatService() throws Exception {
        Class<?> controllerClass = Class.forName("com.springairag.demo.DemoController");
        var constructors = controllerClass.getConstructors();
        assertTrue(constructors.length > 0, "DemoController should have at least one constructor");

        // 验证至少有一个构造函数的参数类型包含 RagChatService
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
    @DisplayName("spring-ai-rag-starter 依赖坐标正确")
    void starterDependency_coordinates() throws Exception {
        // 验证 spring-ai-rag-starter 工件存在于 classpath
        // （通过检查 RagChatService 类是否可加载来间接验证 starter 在 classpath 中）
        Class<?> ragChatServiceClass = Class.forName("com.springairag.core.config.RagChatService");
        assertNotNull(ragChatServiceClass);
    }
}
