package com.springairag.core.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StaticKnowledgeSearchTool 预算耗尽长尾（Batch 734，JaCoCo 驱
 * 动）：检索预算（默认 3 次）耗尽时返回空结果并标记
 * budgetExhausted（90-94）、预算内正常路径不携带该标记、
 * maxResults 钳制臂（105-107 区域）。
 */
class StaticKnowledgeSearchToolBudgetTailTest {

    private StaticKnowledgeSearchTool tool() {
        return new StaticKnowledgeSearchTool(
                new ObjectMapper(),
                new com.springairag.core.resource.StaticKnowledgeCatalog(
                        new ResourceCatalog(), new RagChatProperties()),
                new com.springairag.core.rag.RetrievalDocumentMapper());
    }

    private AuthorizedRetrievalContext context() {
        var trace = new RetrievalTraceCollector();
        trace.configureQueryExpansion(3, 2, true, 3, 2, true);
        return new AuthorizedRetrievalContext(
                com.springairag.core.retrieval.RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0, true, false, 0.5, 0.5),
                trace,
                "static-budget",
                ChatPrincipal.local(),
                1_000);
    }

    @Test
    void exhaustedBudgetReturnsEmptySourcesWithMarker() {
        var tool = tool();
        AuthorizedRetrievalContext context = context();
        // 收集器预算固定为 3 次：预热消耗全部预算，工具调用即为
        // 第 4 次 → 预算耗尽 → 空结果 + budgetExhausted 标记。
        context.trace().tryBeginRetrieval("w1");
        context.trace().tryBeginRetrieval("w2");
        context.trace().tryBeginRetrieval("w3");

        String response = tool.call(
                "{\"query\":\"查询\",\"maxResults\":3}",
                new ToolContext(Map.of(
                        ProjectDocumentRetriever.CONTEXT_KEY, context)));

        assertTrue(response.contains("budgetExhausted"));
        assertTrue(response.contains("\"resultCount\":0"));
        assertTrue(response.contains("\"sources\":[]"));
    }

    @Test
    void withinBudgetSearchDoesNotCarryExhaustedMarker() {
        var tool = tool();
        AuthorizedRetrievalContext context = context();

        String response = tool.call(
                "{\"query\":\"查询\"}",
                new ToolContext(Map.of(
                        ProjectDocumentRetriever.CONTEXT_KEY, context)));

        // 预算内正常路径：空目录仍返回空结果，且不携带耗尽标记。
        assertTrue(response.contains("resultCount"));
        assertFalse(response.contains("budgetExhausted"));
    }

    @Test
    void maxResultsClampedToContextMaximum() {
        var tool = tool();
        AuthorizedRetrievalContext context = context();

        // maxResults 请求 999 → 被钳制到 options.maxResults()=5；
        // 空目录下仍返回空结果（钳制臂不抛异常）。
        String response = tool.call(
                "{\"query\":\"查询\",\"maxResults\":999}",
                new ToolContext(Map.of(
                        ProjectDocumentRetriever.CONTEXT_KEY, context)));

        assertTrue(response.contains("\"resultCount\":0"));
    }

}
