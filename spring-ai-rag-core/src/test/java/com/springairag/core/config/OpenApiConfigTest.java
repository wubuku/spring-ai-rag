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
        assertEquals("1.0.0-SNAPSHOT", openAPI.getInfo().getVersion());
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
    void globalResponseCustomizer_shouldReturnNonNullCustomizer() {
        // Act
        OpenApiCustomizer customizer = config.globalResponseCustomizer();

        // Assert
        assertNotNull(customizer);
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
}
