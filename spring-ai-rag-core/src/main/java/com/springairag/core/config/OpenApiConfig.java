package com.springairag.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
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
        return openApi -> openApi.getPaths().values().forEach(pathItem ->
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
    }
}
