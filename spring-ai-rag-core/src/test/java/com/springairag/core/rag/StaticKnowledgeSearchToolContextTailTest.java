package com.springairag.core.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import com.springairag.core.rag.ProjectDocumentRetriever;
import com.springairag.core.resource.ResourceKind;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StaticKnowledgeSearchTool 守卫长尾（Batch 573，JaCoCo 驱动）：
 * call 无上下文直接抛 ISE、context 缺键/类型不符拒绝、parse 对损坏
 * JSON 的包装。
 */
class StaticKnowledgeSearchToolContextTailTest {

    private StaticKnowledgeSearchTool tool() {
        return new StaticKnowledgeSearchTool(
                new ObjectMapper(),
                new com.springairag.core.resource.StaticKnowledgeCatalog(
                        new ResourceCatalog(), new RagChatProperties()),
                new com.springairag.core.rag.RetrievalDocumentMapper());
    }

    @Test
    void callWithoutContextThrowsIllegalState() {
        var error = assertThrows(IllegalStateException.class,
                () -> tool().call("{\"query\":\"q\"}"));
        assertEquals("Missing server-owned static knowledge context",
                error.getMessage());
    }

    @Test
    void contextRejectsNullContextAndWrongType() {
        var tool = tool();

        assertThrows(IllegalStateException.class,
                () -> tool.call("{}", null));
        assertThrows(IllegalStateException.class,
                () -> tool.call("{}", new ToolContext(Map.of())));
        assertThrows(IllegalStateException.class,
                () -> tool.call("{}", new ToolContext(Map.of(
                        ProjectDocumentRetriever
                                .CONTEXT_KEY, "not-a-context"))));
    }

    @Test
    void parseWrapsMalformedJson() {
        var tool = tool();
        var authorized = new com.springairag.core.chat.AuthorizedRetrievalContext(
                com.springairag.core.retrieval.RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0, true, false, 0.5, 0.5),
                new RetrievalTraceCollector(),
                "static-tail",
                ChatPrincipal.local(),
                1_000);

        assertThrows(IllegalArgumentException.class,
                () -> tool.call("{broken", new ToolContext(Map.of(
                        ProjectDocumentRetriever
                                .CONTEXT_KEY, authorized))));
    }

    @Test
    void blankQueryRejectedAfterNormalization() {
        var tool = tool();
        var authorized = new com.springairag.core.chat.AuthorizedRetrievalContext(
                com.springairag.core.retrieval.RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0, true, false, 0.5, 0.5),
                new RetrievalTraceCollector(),
                "static-tail",
                ChatPrincipal.local(),
                1_000);

        assertThrows(IllegalArgumentException.class,
                () -> tool.call("{\"query\":\"   \"}", new ToolContext(Map.of(
                        ProjectDocumentRetriever
                                .CONTEXT_KEY, authorized))));
    }
}
