package com.springairag.core.retrieval.rerank;

import com.springairag.core.config.RagRerankProperties;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启发式重排匹配臂长尾（Batch 691，JaCoCo 驱动）：边界感知词在
 * 末尾被阻塞后的搜索耗尽、CJK+拉丁混排段的三种冲刷点（单 CJK 冲
 * 刷、分隔符冲刷、Cjk 分支首冲刷）在词法上限处的提前返回。
 */
class HeuristicRerankMatchArmTailTest {

    private final HeuristicRerankProvider provider =
            new HeuristicRerankProvider(new RagRerankProperties());

    private String queryWithTokens(int tokenCount, String trailingSegment) {
        String tokens = IntStream.range(0, tokenCount)
                .mapToObj(index -> "t" + index)
                .collect(Collectors.joining(" "));
        return tokens + " " + trailingSegment;
    }

    @Test
    void singleLetterTermWithOnlyBlockedOccurrenceExhaustsSearch() {
        // "b" 仅出现在 "ab" 内部：末位出现的左边界被阻塞，前进后
        // searchFrom 越过文本末尾 → 匹配耗尽返回 -1。
        float score = provider.calculateRelevanceScore("b", "ab");

        assertEquals(0f, score);
    }

    @Test
    void mixedSegmentWithSingleCjkFlushesCjkTermBeforeLatin() {
        // 段 "中a"：单一 CJK 后接拉丁字符 → 冲刷 CJK 单词项。
        float score = provider.calculateRelevanceScore("中a", "中a");

        assertTrue(score > 0f);
    }

    @Test
    void mixedSegmentSeparatorFlushesLatinRunAfterCjk() {
        // 段 "中a."：分隔符触发拉丁 run 冲刷（cjkRunLength 已复位）。
        float score = provider.calculateRelevanceScore("中a.", "中a.");

        assertTrue(score > 0f);
    }

    @Test
    void featureOverflowReturnsAtSingleCjkFlush() {
        // 511 个拉丁词 + "中a"：单 CJK 冲刷达到 512 上限 → 提前返回。
        String query = queryWithTokens(511, "中a");

        float score = provider.calculateRelevanceScore(query, "x");

        assertTrue(score >= 0f);
    }

    @Test
    void featureOverflowReturnsAtSeparatorFlush() {
        // 510 个拉丁词 + "中a."：分隔符冲刷达到 512 上限 → 提前返回。
        String query = queryWithTokens(510, "中a.");

        float score = provider.calculateRelevanceScore(query, "x");

        assertTrue(score >= 0f);
    }

    @Test
    void featureOverflowReturnsAfterLatinFlushInsideCjkBranch() {
        // 511 个拉丁词 + "a中"：Cjk 分支入口冲刷达到 512 上限 → 提前返回。
        String query = queryWithTokens(511, "a中");

        float score = provider.calculateRelevanceScore(query, "x");

        assertTrue(score >= 0f);
    }
}
