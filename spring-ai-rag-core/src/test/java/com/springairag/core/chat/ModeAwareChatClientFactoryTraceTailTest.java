package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.rag.CitationQueryAugmenter;
import com.springairag.core.rag.ProjectDocumentRetriever;
import com.springairag.core.rag.ProjectRerankPostProcessor;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ModeAwareChatClientFactory 追踪会话与基线长尾（Batch 693，
 * JaCoCo 驱动）：retrievalTraceSession 存在时按 KNOWLEDGE/AGENT
 * 模式构建 attempt 采集器、SERVER 记忆下 null 基线按空处理、
 * AGENT 模式下候选模型缺 ToolCallingChatOptions 的拒绝。
 */
class ModeAwareChatClientFactoryTraceTailTest {

    private RagProperties properties;
    private ProjectDocumentRetriever documentRetriever;
    private ProjectRerankPostProcessor rerankPostProcessor;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getChat().getKnowledge().setQueryTransformer("none");
        documentRetriever = mock(ProjectDocumentRetriever.class);
        when(documentRetriever.retrieve(any())).thenReturn(List.of());
        rerankPostProcessor = mock(ProjectRerankPostProcessor.class);
        when(rerankPostProcessor.process(any(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private ModeAwareChatClientFactory factory() {
        return new ModeAwareChatClientFactory(
                documentRetriever,
                rerankPostProcessor,
                new CitationQueryAugmenter(properties),
                properties,
                List.of(),
                mock(org.springframework.ai.model.tool.ToolCallingManager.class));
    }

    private ChatCommand command(ChatMode mode,
                                MemoryMode memoryMode,
                                RetrievalTraceSession session) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-trace-tail", principal,
                principal.memoryConversationId("session-trace-tail"),
                mode, memoryMode, null, null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                java.util.Map.of(),
                java.util.List.of(),
                java.util.List.of(),
                session,
                null,
                null);
    }

    private ChatModelRouter.ChatModelCandidate candidate(
            boolean toolCalling) {
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(toolCalling
                ? ToolCallingChatOptions.builder()
                        .model("test-model")
                        .build()
                : ChatOptions.builder().model("test-model").build());
        return new ChatModelRouter.ChatModelCandidate(
                "trace-tail", model,
                new MultiModelProperties.ModelCapabilities(true, false));
    }

    @Test
    void traceSessionBuildsCollectorUnderKnowledgeBudget() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "op-trace", "session-trace-tail");

        var attempt = factory().create(
                command(ChatMode.KNOWLEDGE, MemoryMode.STATELESS, session),
                candidate(false),
                java.util.List.of());

        assertNotNull(attempt);
    }

    @Test
    void traceSessionBuildsCollectorUnderAgentBudget() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "op-trace", "session-trace-tail");

        var attempt = factory().create(
                command(ChatMode.AGENT, MemoryMode.STATELESS, session),
                candidate(true),
                java.util.List.of());

        assertNotNull(attempt);
    }

    @Test
    void serverMemoryWithNullBaselineTreatsBaselineAsEmpty() {
        var attempt = factory().create(
                command(ChatMode.KNOWLEDGE, MemoryMode.SERVER, null),
                candidate(false),
                null);

        assertNotNull(attempt);
    }

    @Test
    void agentModeRejectsCandidateWithoutToolCallingOptions() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> factory().create(
                        command(ChatMode.AGENT, MemoryMode.STATELESS, null),
                        candidate(false),
                        java.util.List.of()));

        assertTrue(error.getMessage()
                .contains("does not expose ToolCallingChatOptions"));
    }
}
