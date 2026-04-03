package com.springairag.demo.component;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 组件级集成演示应用
 *
 * <p>展示如何在已有 Spring AI 项目中，选择性地引入 RAG Advisor。
 * 与 demo-basic-rag 的区别：这里不使用 spring-ai-rag-starter，
 * 而是自己手动把 HybridSearchAdvisor / QueryRewriteAdvisor / RerankAdvisor
 * 挂载到 ChatClient 上。
 *
 * <p>适用场景：
 * <ul>
 *   <li>已有 Spring AI ChatClient，不想引入完整 Starter</li>
 *   <li>想细粒度控制 Advisor 链的组装方式</li>
 *   <li>想理解 RAG Pipeline 内部是怎么串起来的</li>
 * </ul>
 */
@SpringBootApplication
public class ComponentLevelDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(ComponentLevelDemoApplication.class, args);
    }
}
