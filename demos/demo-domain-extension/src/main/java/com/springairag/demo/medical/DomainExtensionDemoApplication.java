package com.springairag.demo.medical;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 领域扩展示例应用 — 医疗问诊领域
 *
 * <p>展示如何通过 DomainRagExtension + PromptCustomizer
 * 为通用 RAG 框架添加特定领域的智能问答能力。
 *
 * <p>启动后访问：
 * <ul>
 *   <li>医疗问诊: POST /api/v1/medical/consult</li>
 *   <li>快速问诊: GET /api/v1/medical/quick?q=头疼怎么办</li>
 *   <li>默认 RAG: POST /api/v1/rag/chat/ask（无领域扩展）</li>
 *   <li>健康检查: http://localhost:8081/actuator/health</li>
 * </ul>
 */
@SpringBootApplication
public class DomainExtensionDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DomainExtensionDemoApplication.class, args);
    }
}
