package com.springairag.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SpringDoc OpenAPI 全局配置
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private int serverPort;

    @Bean
    public OpenAPI springRagOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Spring AI RAG Service API")
                        .description("""
                                通用 RAG（检索增强生成）服务框架 API。
                                
                                ## 核心能力
                                - **文档管理**：CRUD、批量操作、分块、嵌入向量生成
                                - **检索**：向量检索 + 全文检索混合检索，支持权重调节
                                - **问答**：非流式 + SSE 流式问答，支持多轮对话记忆
                                - **评估**：Precision@K / MRR / NDCG 检索质量评估
                                - **A/B 实验**：检索策略对比实验框架
                                - **监控**：告警、SLO 检查、性能基准
                                
                                ## 模型无关
                                支持 OpenAI、DeepSeek、Anthropic 等多种 LLM，通过配置切换。
                                """)
                        .version("1.0.0-SNAPSHOT")
                        .contact(new Contact()
                                .name("Spring AI RAG")
                                .url("https://github.com/yangjiefeng/spring-ai-rag"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server().url("http://localhost:" + serverPort).description("本地开发")));
    }

    /**
     * 为所有端点添加通用错误响应
     */
    @Bean
    public OpenApiCustomizer globalResponseCustomizer() {
        return openApi -> {
            // Add ErrorResponse schema (RFC 7807 Problem Details)
            if (openApi.getComponents() == null) {
                openApi.setComponents(new io.swagger.v3.oas.models.Components());
            }
            java.util.Map<String, io.swagger.v3.oas.models.media.Schema> schemas = openApi.getComponents().getSchemas();
            if (schemas == null) {
                schemas = new java.util.HashMap<>();
                openApi.getComponents().setSchemas(schemas);
            }
            schemas.put("ErrorResponse", createErrorResponseSchema());

            // Add 400/500 error responses to all endpoints
            openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation -> {
                    ApiResponses responses = operation.getResponses();
                    if (responses == null) {
                        responses = new ApiResponses();
                        operation.setResponses(responses);
                    }
                    if (!responses.containsKey("400")) {
                        responses.addApiResponse("400", new ApiResponse()
                                .description("请求参数错误（校验失败）"));
                    }
                    if (!responses.containsKey("500")) {
                        responses.addApiResponse("500", new ApiResponse()
                                .description("服务器内部错误"));
                    }
                }));
        };
    }

    /**
     * RFC 7807 Problem Details schema for error responses.
     * Spring Boot 3.x uses ProblemDetail for error handling.
     */
    private Schema<?> createErrorResponseSchema() {
        Schema<?> schema = new Schema<>();
        schema.setType("object");
        schema.setDescription("RFC 7807 Problem Details error response");
        schema.addProperty("type", new Schema<>().type("string").description("错误类型 URI"));
        schema.addProperty("title", new Schema<>().type("string").description("错误标题"));
        schema.addProperty("status", new Schema<>().type("integer").format("int32").description("HTTP 状态码"));
        schema.addProperty("detail", new Schema<>().type("string").description("错误详情"));
        schema.addProperty("instance", new Schema<>().type("string").description("错误实例 URI"));
        schema.addProperty("error", new Schema<>().type("string").description("错误代码"));
        schema.addProperty("message", new Schema<>().type("string").description("错误消息"));
        schema.addProperty("timestamp", new Schema<>().type("string").format("date-time").description("错误发生时间"));
        schema.addProperty("path", new Schema<>().type("string").description("请求路径"));
        return schema;
    }
}
