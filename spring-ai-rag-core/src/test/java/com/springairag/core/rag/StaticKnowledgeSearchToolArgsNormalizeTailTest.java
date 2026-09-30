package com.springairag.core.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StaticKnowledgeSearchTool 参数归一长尾（Batch 740，JaCoCo 驱
 * 动）：number() 对非 Number/缺失 chunkIndex 回退 0（137/176）、
 * 空白 toolInput 经 parse 归一为 "{}" 后触发 blank query 拒绝
 * （158）。
 */
class StaticKnowledgeSearchToolArgsNormalizeTailTest {

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
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0, true, false, 0.5, 0.5),
                trace,
                "static-args",
                ChatPrincipal.local(),
                1_000);
    }

    @Test
    void numberFallsBackToZeroForMissingOrNonNumberValue()
            throws Exception {
        Method method = StaticKnowledgeSearchTool.class
                .getDeclaredMethod("number", Object.class);
        method.setAccessible(true);

        Assertions.assertEquals(0, method.invoke(tool(), (Object) null));
        Assertions.assertEquals(3, method.invoke(tool(), 3));
        Assertions.assertEquals(0, method.invoke(tool(), "not-a-number"));
    }

    @Test
    void blankToolInputNormalizesToEmptyArgsThenBlankQueryRejected() {
        var authorized = context();

        assertThrows(IllegalArgumentException.class,
                () -> tool().call("   ", new ToolContext(Map.of(
                        ProjectDocumentRetriever.CONTEXT_KEY, authorized))));
    }
}
