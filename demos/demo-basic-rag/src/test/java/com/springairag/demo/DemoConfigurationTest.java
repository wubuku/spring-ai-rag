package com.springairag.demo;

import com.springairag.api.dto.ChatRequest;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
 * export DEEPSEEK_API_KEY=xxx RAG_EMBEDDING_API_KEY=xxx
 * mvn spring-boot:run
 * curl http://localhost:8081/demo/ask?q=什么是RAG
 * curl -X POST http://localhost:8081/demo/chat \
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

        // 原来第二个 assertNotNull 只断 @RequestMapping 注解在不在，
        // 挂在哪个路径上没人管——把 @RequestMapping 的值删掉或改成别的，
        // 这条照样绿，而路由已经整体挪了位。
        org.springframework.web.bind.annotation.RequestMapping mapping =
                controllerClass.getAnnotation(
                        org.springframework.web.bind.annotation.RequestMapping.class);
        assertNotNull(mapping, "DemoController 必须有类级 @RequestMapping");
        assertEquals(List.of("/demo"), List.of(mapping.value()),
                "DemoController 的类级映射路径");

        // /demo/ask 与 /demo/chat 是 docker 镜像里暴露的两个端点
        // （docker/Dockerfile:59 装了本 demo 的 jar），路径变了要能看出来。
        assertTrue(hasMethodMapping(controllerClass,
                        org.springframework.web.bind.annotation.GetMapping.class, "/ask"),
                "DemoController 应保留 GET /demo/ask");
        assertTrue(hasMethodMapping(controllerClass,
                        org.springframework.web.bind.annotation.PostMapping.class, "/chat"),
                "DemoController 应保留 POST /demo/chat");
    }

    private static boolean hasMethodMapping(Class<?> type,
            Class<? extends java.lang.annotation.Annotation> mappingType,
            String expectedPath) {
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            var mapping = method.getAnnotation(mappingType);
            if (mapping == null) {
                continue;
            }
            String[] paths = mappingType == org.springframework.web.bind.annotation.GetMapping.class
                    ? ((org.springframework.web.bind.annotation.GetMapping) mapping).value()
                    : ((org.springframework.web.bind.annotation.PostMapping) mapping).value();
            for (String path : paths) {
                if (expectedPath.equals(path)) {
                    return true;
                }
            }
        }
        return false;
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
        // 原来是 Class.forName("...RagChatService") + assertNotNull：
        // forName 在类缺失时直接抛 ClassNotFoundException，类在时必然非空，
        // 断言等于没写。而用例名字承诺的是 Coordinates——
        // 真正会错的是 pom 里的 groupId/artifactId 拼错、或者版本被钉死成
        // 一个和本仓库对不上的旧值。这些读 pom 就能断。
        java.nio.file.Path pom = java.nio.file.Path.of("pom.xml");
        java.nio.file.Path rootPom = java.nio.file.Path.of("..", "..", "pom.xml");
        assertTrue(java.nio.file.Files.exists(pom), "demo 的 pom.xml 不在预期位置：" + pom.toAbsolutePath());
        assertTrue(java.nio.file.Files.exists(rootPom),
                "仓库根 pom.xml 不在预期位置：" + rootPom.toAbsolutePath());

        String pomXml = java.nio.file.Files.readString(pom);
        String rootXml = java.nio.file.Files.readString(rootPom);

        // 依赖坐标：groupId 与 artifactId 必须成对出现，且版本走属性引用。
        assertTrue(pomXml.contains("<groupId>com.springairag</groupId>"),
                "demo 必须依赖 com.springairag 组");
        assertTrue(pomXml.contains("<artifactId>spring-ai-rag-starter</artifactId>"),
                "demo 必须依赖 spring-ai-rag-starter");
        assertTrue(pomXml.contains("<version>${spring-ai-rag.version}</version>"),
                "starter 版本必须走 ${spring-ai-rag.version} 属性，不能写死");

        // 那个属性解析出来要和仓库根 pom 的项目版本一致——
        // 写死 1.0.0 而仓库已经到 1.1.0 的话，只有这条会红。
        java.util.regex.Matcher prop =
                java.util.regex.Pattern.compile(
                        "<spring-ai-rag.version>([^<]+)</spring-ai-rag.version>")
                        .matcher(pomXml);
        assertTrue(prop.find(), "demo pom 里找不到 spring-ai-rag.version 属性");
        java.util.regex.Matcher rootVersion =
                java.util.regex.Pattern.compile(
                        "<artifactId>spring-ai-rag</artifactId>\\s*<version>([^<]+)</version>")
                        .matcher(rootXml);
        assertTrue(rootVersion.find(), "仓库根 pom 里读不到项目版本");
        assertEquals(rootVersion.group(1), prop.group(1),
                "demo 引用的 starter 版本与仓库当前版本不一致");

        // 顺带确认类确实在 classpath 上（forName 缺失时会抛，正好当断言用）。
        assertNotNull(Class.forName("com.springairag.core.config.RagChatService"));
    }
}
