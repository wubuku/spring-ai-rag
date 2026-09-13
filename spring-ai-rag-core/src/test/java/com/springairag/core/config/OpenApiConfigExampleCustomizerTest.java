package com.springairag.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenApiConfig 残余（Batch 370）：示例响应定制器对已知 opId 注入
 * 表驱动示例、无 opId/无 responses 的操作跳过、示例响应缺失时
 * 创建 ApiResponse 与 Content。
 */
class OpenApiConfigExampleCustomizerTest {

    private static final List<String> KNOWN_OPERATION_IDS = List.of(
            "chatAsk", "chatStream", "search", "listDocuments",
            "batchCreateDocuments", "createCollection", "getChatHistory",
            "getCacheStats", "getRagMetrics");

    private Operation operation(String opId) {
        Operation operation = new Operation();
        if (opId != null) {
            operation.setOperationId(opId);
        }
        return operation;
    }

    @Test
    void customizerInjectsExamplesForAllKnownOperationIds() {
        OpenApiConfig config = new OpenApiConfig();
        OpenApiCustomizer customizer = config.exampleResponseCustomizer();

        OpenAPI openApi = new OpenAPI();
        Paths paths = new Paths();
        for (String opId : KNOWN_OPERATION_IDS) {
            Operation operation = operation(opId);
            operation.setResponses(new ApiResponses());
            paths.addPathItem("/p/" + opId,
                    new PathItem().get(operation));
        }
        openApi.setPaths(paths);

        customizer.customise(openApi);

        for (String opId : KNOWN_OPERATION_IDS) {
            Operation operation = openApi.getPaths()
                    .get("/p/" + opId).getGet();
            var responses = operation.getResponses();
            var entry = responses.entrySet().iterator().next();
            var media = entry.getValue().getContent().entrySet()
                    .iterator().next().getValue();
            assertNotNull(media.getExamples().get("default"));
            assertTrue(entry.getKey().equals("200")
                    || entry.getKey().equals("201"),
                    "unexpected response code " + entry.getKey());
            assertEquals("object",
                    media.getSchema().getType());
        }
    }

    @Test
    void customizerSkipsOperationsWithoutOpIdOrResponses() {
        OpenApiConfig config = new OpenApiConfig();
        OpenApiCustomizer customizer = config.exampleResponseCustomizer();

        Operation noOpId = operation(null);
        noOpId.setResponses(new ApiResponses());
        Operation noResponses = operation("chatAsk");
        OpenAPI openApi = new OpenAPI();
        openApi.setPaths(new Paths()
                .addPathItem("/a", new PathItem().get(noOpId))
                .addPathItem("/b", new PathItem().post(noResponses)));

        customizer.customise(openApi);

        assertNull(openApi.getPaths().get("/a").getGet().getOperationId());
        assertNull(noResponses.getResponses());
        assertNull(noOpId.getResponses().get("200"));
    }

    @Test
    void exampleResponseReusesExistingResponseEntry() {
        OpenApiConfig config = new OpenApiConfig();
        OpenApiCustomizer customizer = config.exampleResponseCustomizer();

        Operation operation = operation("chatAsk");
        ApiResponses responses = new ApiResponses();
        var existing = new io.swagger.v3.oas.models.responses.ApiResponse();
        responses.addApiResponse("200", existing);
        operation.setResponses(responses);
        OpenAPI openApi = new OpenAPI();
        openApi.setPaths(new Paths().addPathItem(
                "/c", new PathItem().get(operation)));

        customizer.customise(openApi);

        // 已有 200 响应条目被复用（内容追加而非重建）。
        assertSame(existing, operation.getResponses().get("200"));
        assertNotNull(existing.getContent().get("application/json"));
    }
}
