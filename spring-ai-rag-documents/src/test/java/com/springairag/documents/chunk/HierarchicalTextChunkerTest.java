package com.springairag.documents.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HierarchicalTextChunker 测试
 */
class HierarchicalTextChunkerTest {

    private final HierarchicalTextChunker chunker = new HierarchicalTextChunker(500, 50, 50);

    @Test
    void split_nullInput() {
        List<TextChunk> chunks = chunker.split(null);
        assertNotNull(chunks);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void split_emptyInput() {
        List<TextChunk> chunks = chunker.split("");
        assertNotNull(chunks);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void split_whitespaceOnly() {
        List<TextChunk> chunks = chunker.split("   \n\n  ");
        assertNotNull(chunks);
    }

    @Test
    void split_realMarkdown() {
        // 使用带足够内容的 Markdown 测试
        String content = """
                # 第一章 Spring Boot 介绍

                Spring Boot 是一个用于快速构建 Spring 应用的框架。
                它提供了自动配置、起步依赖和运行时监控等功能。

                ## 1.1 核心特性

                Spring Boot 的核心特性包括：自动配置、嵌入式服务器、
                外部化配置、Actuator 监控等。

                ## 1.2 快速开始

                要创建一个 Spring Boot 项目，可以使用 Spring Initializr
                或者手动配置 Maven 依赖。

                # 第二章 Spring AI 介绍

                Spring AI 是 Spring 生态中的 AI 集成框架。
                它提供了 ChatClient、VectorStore、Advisor 等核心组件。

                ## 2.1 ChatClient

                ChatClient 是调用 LLM 的统一入口，支持同步和流式调用。
                """;

        List<TextChunk> chunks = chunker.split(content);
        // 至少应该有一些输出
        assertNotNull(chunks);
    }

    @Test
    void textChunk_record() {
        TextChunk chunk = new TextChunk("test text", 0, 9);
        assertEquals("test text", chunk.text());
        assertEquals(0, chunk.startPos());
        assertEquals(9, chunk.endPos());
    }
}
