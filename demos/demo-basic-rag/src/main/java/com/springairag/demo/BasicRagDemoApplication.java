package com.springairag.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 基础 RAG 示例应用
 *
 * <p>启动后访问：
 * <ul>
 *   <li>Swagger UI: http://localhost:8080/swagger-ui.html</li>
 *   <li>RAG 问答: POST http://localhost:8080/api/v1/rag/chat/ask</li>
 *   <li>健康检查: http://localhost:8080/actuator/health</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = {"com.springairag", "com.springairag.demo"})
@ConfigurationPropertiesScan
public class BasicRagDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(BasicRagDemoApplication.class, args);
    }
}
