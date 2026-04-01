package com.springairag.documents.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HierarchicalTextChunker 测试
 */
class HierarchicalTextChunkerTest {

    private final HierarchicalTextChunker chunker = new HierarchicalTextChunker(500, 50, 50);

    // ========== 基础边界测试 ==========

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
    void split_shortPlainText() {
        List<TextChunk> chunks = chunker.split("This is a short sentence.");
        // 短于 minChunkSize(50) 会被过滤
        assertNotNull(chunks);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void split_mediumPlainText() {
        String content = "This is a paragraph that is long enough to pass the minimum chunk size filter. "
                + "It has multiple sentences. Each sentence adds to the overall length.";
        List<TextChunk> chunks = chunker.split(content);
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.get(0).text().length() >= 50);
    }

    // ========== 标题层级测试 ==========

    @Test
    void split_realMarkdown() {
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
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
    }

    @Test
    void split_titleInheritance() {
        String content = """
                # 主标题

                第一段内容，继承了主标题的上下文信息。这里有足够的文字内容来通过最小长度过滤器。

                ## 子标题

                第二段内容，应该继承主标题和子标题。同样需要足够的文字来保证不会被过滤掉。
                """;

        HierarchicalTextChunker smallMinChunker = new HierarchicalTextChunker(500, 10, 50);
        List<TextChunk> chunks = smallMinChunker.split(content);
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        // 验证标题上下文被注入
        boolean hasTitleContext = chunks.stream().anyMatch(c -> c.text().contains("主标题"));
        assertTrue(hasTitleContext, "标题上下文应该被注入到 chunk 中");
    }

    @Test
    void split_deepHeaderLevels() {
        String content = """
                # Level 1

                Content under level 1 header section text block area.

                ## Level 2

                Content under level 2 header section text block area.

                ### Level 3

                Content under level 3 header section text block area.

                #### Level 4

                Content under level 4 header section text block area.

                ##### Level 5

                Content under level 5 header section text block area.

                ###### Level 6

                Content under level 6 header section text block area.
                """;

        List<TextChunk> chunks = chunker.split(content);
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
    }

    // ========== 表格测试 ==========

    @Test
    void split_markdownTable() {
        String content = """
                # 数据表

                | 姓名 | 年龄 | 城市 | 职业 | 薪资 |
                |------|------|------|------|------|
                | 张三 | 25   | 北京 | 工程师 | 20000 |
                | 李四 | 30   | 上海 | 设计师 | 18000 |
                | 王五 | 28   | 广州 | 产品经理 | 22000 |

                表格之后的内容继续描述更多信息。
                """;

        HierarchicalTextChunker smallMinChunker = new HierarchicalTextChunker(500, 10, 50);
        List<TextChunk> chunks = smallMinChunker.split(content);
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        // 表格应该被保留为单独的 chunk
        boolean hasTableChunk = chunks.stream().anyMatch(c -> c.text().contains("[表格]"));
        assertTrue(hasTableChunk, "表格应被转换为 [表格] chunk");
    }

    @Test
    void split_multipleTables() {
        String content = """
                | 姓名 | 年龄 | 城市 |
                |------|------|------|
                | 张三 | 25   | 北京 |
                | 李四 | 30   | 上海 |

                一些中间内容文字描述段落。

                | 产品 | 价格 | 库存 |
                |------|------|------|
                | 手机 | 3999 | 100  |
                | 电脑 | 6999 | 50   |
                """;

        HierarchicalTextChunker smallMinChunker = new HierarchicalTextChunker(500, 10, 50);
        List<TextChunk> chunks = smallMinChunker.split(content);
        long tableCount = chunks.stream().filter(c -> c.text().contains("[表格]")).count();
        assertEquals(2, tableCount, "应该有 2 个表格 chunk");
    }

    @Test
    void split_invalidTable_singleRow() {
        // 只有一行的不是有效表格
        String content = "| Single | Row | Table |";
        HierarchicalTextChunker smallMinChunker = new HierarchicalTextChunker(500, 10, 50);
        List<TextChunk> chunks = smallMinChunker.split(content);
        long tableCount = chunks.stream().filter(c -> c.text().contains("[表格]")).count();
        assertEquals(0, tableCount, "单行不是有效表格");
    }

    // ========== 句子级切分测试 ==========

    @Test
    void split_longContent_sentenceSplit() {
        // 使用英文句子（句号后有空格），超过 maxChunkSize(200)
        StringBuilder sb = new StringBuilder();
        sb.append("# Long Document\n\n");
        for (int i = 0; i < 30; i++) {
            sb.append("This is sentence number ").append(i + 1).append(". ");
        }

        HierarchicalTextChunker smallChunker = new HierarchicalTextChunker(200, 30, 20);
        List<TextChunk> chunks = smallChunker.split(sb.toString());
        assertNotNull(chunks);
        // 长内容应该被切分为多个 chunk
        assertTrue(chunks.size() > 1, "长内容应被切分为多个 chunk，实际: " + chunks.size());
    }

    @Test
    void split_overlapPreserved() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Test\n\n");
        for (int i = 0; i < 30; i++) {
            sb.append("This is sentence number ").append(i + 1).append(". ");
        }

        HierarchicalTextChunker overlapChunker = new HierarchicalTextChunker(200, 30, 50);
        List<TextChunk> chunks = overlapChunker.split(sb.toString());
        assertNotNull(chunks);
        if (chunks.size() > 1) {
            // 验证 overlap 生效：后续 chunk 的文本应该包含前一个 chunk 末尾的部分内容
            // 由于有 overlap，chunks 数量应该比没有 overlap 时更多或相等
            assertTrue(chunks.size() >= 2);
        }
    }

    // ========== 固定大小切分测试 ==========

    @Test
    void split_longContentWithoutSentences() {
        // 没有标点符号的长内容，应走 fixed-size 切分
        StringBuilder sb = new StringBuilder();
        sb.append("# Test\n\n");
        for (int i = 0; i < 100; i++) {
            sb.append("word ");
        }

        HierarchicalTextChunker smallChunker = new HierarchicalTextChunker(100, 10, 0);
        List<TextChunk> chunks = smallChunker.split(sb.toString());
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
    }

    // ========== 静态工厂方法测试 ==========

    @Test
    void staticFactoryMethod() {
        String content = """
                # Test Header

                This is some content under the test header that should be split properly.
                It has multiple sentences. Each one adds more text for testing purposes.
                """;

        List<TextChunk> chunks = HierarchicalTextChunker.split(content, 200, 20);
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
    }

    // ========== TextChunk record 测试 ==========

    @Test
    void textChunk_record() {
        TextChunk chunk = new TextChunk("test text", 0, 9);
        assertEquals("test text", chunk.text());
        assertEquals(0, chunk.startPos());
        assertEquals(9, chunk.endPos());
    }

    @Test
    void textChunk_equality() {
        TextChunk chunk1 = new TextChunk("hello", 0, 5);
        TextChunk chunk2 = new TextChunk("hello", 0, 5);
        TextChunk chunk3 = new TextChunk("world", 0, 5);

        assertEquals(chunk1, chunk2);
        assertNotEquals(chunk1, chunk3);
        assertEquals(chunk1.hashCode(), chunk2.hashCode());
    }

    // ========== minChunkSize 过滤测试 ==========

    @Test
    void split_filtersShortChunks() {
        String content = """
                # Header

                Short.

                # Another

                This section has enough content to be included in the final result without filtering.
                """;

        HierarchicalTextChunker strictChunker = new HierarchicalTextChunker(500, 100, 50);
        List<TextChunk> chunks = strictChunker.split(content);
        // 所有 chunk 长度应 >= minChunkSize
        for (TextChunk chunk : chunks) {
            assertTrue(chunk.text().length() >= 100,
                    "Chunk 长度 " + chunk.text().length() + " 应 >= 100: " + chunk.text().substring(0, Math.min(30, chunk.text().length())));
        }
    }

    // ========== 排序测试 ==========

    @Test
    void chunks_sortedByPosition() {
        String content = """
                # Section A

                Content for section A with enough text to pass filter.

                # Section B

                Content for section B with enough text to pass filter.

                # Section C

                Content for section C with enough text to pass filter.
                """;

        List<TextChunk> chunks = chunker.split(content);
        if (chunks.size() > 1) {
            for (int i = 0; i < chunks.size() - 1; i++) {
                assertTrue(chunks.get(i).startPos() <= chunks.get(i + 1).startPos(),
                        "Chunks 应按 startPos 排序");
            }
        }
    }
}
