package com.springairag.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Basic RAG demo application.
 *
 * <p>After starting, access:
 * <ul>
 *   <li>Swagger UI: http://localhost:8080/swagger-ui.html</li>
 *   <li>RAG Q&amp;A: POST http://localhost:8080/api/v1/rag/chat/ask</li>
 *   <li>Health check: http://localhost:8080/actuator/health</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = {"com.springairag", "com.springairag.demo"})
@ConfigurationPropertiesScan
public class BasicRagDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(BasicRagDemoApplication.class, args);
    }
}
