package com.springairag.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring AI RAG 应用入口
 */
@SpringBootApplication(scanBasePackages = "com.springairag")
@ConfigurationPropertiesScan("com.springairag.core.config")
public class SpringAiRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiRagApplication.class, args);
    }
}
