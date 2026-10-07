package com.springairag.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenApiConfig Unit Tests
 */
class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void springRagOpenAPI_shouldCreateValidOpenAPI() {
        // Act
        OpenAPI openAPI = config.springRagOpenAPI();

        // Assert
        assertNotNull(openAPI);
        assertNotNull(openAPI.getInfo());
        assertEquals("Spring AI RAG Service API", openAPI.getInfo().getTitle());
        assertEquals("1.0.0", openAPI.getInfo().getVersion());
        assertNotNull(openAPI.getInfo().getDescription());
        assertTrue(openAPI.getInfo().getDescription().contains("RAG"));
        assertNotNull(openAPI.getInfo().getContact());
        assertNotNull(openAPI.getInfo().getLicense());
        assertNotNull(openAPI.getServers());
        assertFalse(openAPI.getServers().isEmpty());
    }

    @Test
    void springRagOpenAPI_descriptionShouldCoverCoreCapabilities() {
        OpenAPI openAPI = config.springRagOpenAPI();
        String desc = openAPI.getInfo().getDescription();

        assertTrue(desc.contains("Document Management"));
        assertTrue(desc.contains("Retrieval"));
        assertTrue(desc.contains("Evaluation"));
        assertTrue(desc.contains("Monitoring"));
        assertTrue(desc.contains("Model-Agnostic"));
    }

    @Test
    void globalResponseCustomizer_shouldAdd400And500Responses() {
        // Arrange
        OpenAPI openAPI = new OpenAPI();
        Paths paths = new Paths();
        io.swagger.v3.oas.models.PathItem pathItem = new io.swagger.v3.oas.models.PathItem();
        io.swagger.v3.oas.models.Operation operation = new io.swagger.v3.oas.models.Operation();
        operation.setSummary("test operation");
        pathItem.setGet(operation);
        paths.addPathItem("/api/v1/test", pathItem);
        openAPI.setPaths(paths);

        // Act
        OpenApiCustomizer customizer = config.globalResponseCustomizer();
        customizer.customise(openAPI);

        // Assert
        assertNotNull(openAPI.getPaths().get("/api/v1/test").getGet().getResponses());
        assertTrue(openAPI.getPaths().get("/api/v1/test").getGet().getResponses().containsKey("400"));
        assertTrue(openAPI.getPaths().get("/api/v1/test").getGet().getResponses().containsKey("500"));
    }

    @Test
    void globalResponseCustomizer_toleratesAnOpenApiWithoutPaths() {
        // Batch 957：删掉了紧挨着的 globalResponseCustomizer_shouldReturnNonNullCustomizer。
        // 它只断 customizer 非空，而下面那条 shouldAdd400And500Responses
        // 既取了同一个 customizer、又真的跑了一遍并断言输出里多了 400/500
        // ——前者一条都没多证明，纯重复。
        // 换上的这条断的是另一件事：没有 paths 的 OpenAPI（启动早期、
        // 或者全部路径被过滤掉）不能把定制器本身搞炸。
        OpenAPI empty = new OpenAPI();

        OpenApiCustomizer customizer = config.globalResponseCustomizer();

        assertDoesNotThrow(() -> customizer.customise(empty));
        assertTrue(empty.getPaths() == null || empty.getPaths().isEmpty(),
                "没有路径时不应凭空造出路径");
    }
}
