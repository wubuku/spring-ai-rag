package com.springairag.demo.multimodel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring AI RAG 多模型演示启动器
 *
 * <p>展示多模型 RAG 架构：
 * <ul>
 *   <li>ModelRegistry：注册并管理所有 ChatModel Bean</li>
 *   <li>ChatModelRouter：根据请求参数动态选择目标模型</li>
 *   <li>模型对比：通过 ModelComparisonService 并行对比多个模型</li>
 * </ul>
 *
 * <p>配置多模型：在 application.yml 中设置 app.llm.provider 切换默认模型，
 * 或通过 REST API 动态选择。
 *
 * @see <a href="../../../../../../docs/multi-model-enhancement-plan.md">多模型增强方案</a>
 */
@SpringBootApplication
public class MultiModelDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultiModelDemoApplication.class, args);
    }
}
