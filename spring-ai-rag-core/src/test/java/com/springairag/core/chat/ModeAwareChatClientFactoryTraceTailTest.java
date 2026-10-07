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
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 *
 * <p>Batch 953：三条用例原本的唯一断言是 {@code assertNotNull(attempt)}，
 * 而 {@code create(...)} 走的是"没有异常就返回一个 record"的路子，
 * 这条断言在数学上恒成立——名字里声称的"预算""基线按空处理"
 * 一个都没被验证。现在改为钉机制：
 * <ul>
 *   <li>预算按模式选取：把 knowledge 与 agent 的上限设成<strong>不同</strong>的值
 *       （默认两者都是 3，断言不出差别），再用采集器自己的
 *       {@code tryBeginRetrieval} 数它到底放行了几次；</li>
 *   <li>采集器确实挂在会话上：{@code parentSession()} 是那个 session，
 *       且预算耗尽会回写到 session（{@code budgetExhausted()}）；</li>
 *   <li>基线：null 基线 → 记忆为空，同一夹具喂一条基线 → 记忆里有它。
 *       只断言"空"会被"记忆根本没建起来"骗过去，所以两条一起断言。</li>
 * </ul>
 */
class ModeAwareChatClientFactoryTraceTailTest {

    /** KNOWLEDGE 模式的检索次数上限，与 {@link #AGENT_RETRIEVAL_BUDGET} 必须不同。 */
    private static final int KNOWLEDGE_RETRIEVAL_BUDGET = 2;
    /** AGENT 模式的检索次数上限，与上面刻意不同，用来证明断言分得清模式。 */
    private static final int AGENT_RETRIEVAL_BUDGET = 5;

    private static final String SESSION_ID = "session-trace-tail";

    private RagProperties properties;
    private ProjectDocumentRetriever documentRetriever;
    private ProjectRerankPostProcessor rerankPostProcessor;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getChat().getKnowledge().setQueryTransformer("none");
        properties.getChat().getKnowledge()
                .setMaxRetrievalQueries(KNOWLEDGE_RETRIEVAL_BUDGET);
        properties.getChat().getAgent()
                .setMaxRetrievalCalls(AGENT_RETRIEVAL_BUDGET);
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
                "问题", SESSION_ID, principal,
                principal.memoryConversationId(SESSION_ID),
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

    /** 记忆键由 principal 与 sessionId 派生，测试必须用同一条路径算出来。 */
    private String memoryConversationId() {
        return ChatPrincipal.local().memoryConversationId(SESSION_ID);
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

    /**
     * 数采集器到底放行了多少次检索：从第 1 次开始连续调
     * {@code tryBeginRetrieval}，数到第一次被拒为止。
     */
    private int allowedRetrievals(RetrievalTraceCollector trace) {
        int allowed = 0;
        while (trace.tryBeginRetrieval("q" + allowed)) {
            allowed++;
        }
        return allowed;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sessionAttempts(RetrievalTraceSession session) {
        Object attempts = session.toMetadata(false).get("attempts");
        return (List<Map<String, Object>>) attempts;
    }

    @Test
    void traceSessionBuildsCollectorUnderKnowledgeBudget() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "op-trace", SESSION_ID);

        var attempt = factory().create(
                command(ChatMode.KNOWLEDGE, MemoryMode.STATELESS, session),
                candidate(false),
                java.util.List.of());

        RetrievalTraceCollector trace = attempt.retrievalContext().trace();
        // 采集器挂在会话上，不是游离的 new RetrievalTraceCollector(...)
        assertSame(session, trace.parentSession());
        assertNotNull(trace.attemptKey());
        // 会话侧也登记了这个 attempt
        assertEquals(1, sessionAttempts(session).size());
        assertEquals(trace.attemptKey(),
                sessionAttempts(session).get(0).get("key"));

        // 预算取的是 knowledge.maxRetrievalQueries，不是 agent.maxRetrievalCalls
        assertEquals(KNOWLEDGE_RETRIEVAL_BUDGET, allowedRetrievals(trace));
        assertTrue(trace.lastBudgetExhausted());
        assertTrue(session.budgetExhausted());
        assertEquals("trace-tail:",
                trace.attemptKey().substring(0, "trace-tail:".length()));
    }

    @Test
    void traceSessionBuildsCollectorUnderAgentBudget() {
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "op-trace", SESSION_ID);

        var attempt = factory().create(
                command(ChatMode.AGENT, MemoryMode.STATELESS, session),
                candidate(true),
                java.util.List.of());

        RetrievalTraceCollector trace = attempt.retrievalContext().trace();
        assertSame(session, trace.parentSession());
        assertEquals(1, sessionAttempts(session).size());
        // 与上一条唯一的差别就是模式：同两个上限（2 / 5），
        // 这里必须读到 agent 那个值，否则"按模式选预算"没被验证。
        assertEquals(AGENT_RETRIEVAL_BUDGET, allowedRetrievals(trace));
        assertTrue(trace.lastBudgetExhausted());
        assertTrue(session.budgetExhausted());
    }

    @Test
    void serverMemoryWithNullBaselineTreatsBaselineAsEmpty() {
        var attempt = factory().create(
                command(ChatMode.KNOWLEDGE, MemoryMode.SERVER, null),
                candidate(false),
                null);

        ChatMemory memory = attempt.memory();
        assertNotNull(memory, "SERVER 模式必须建出记忆");
        assertEquals(java.util.List.of(), memory.get(memoryConversationId()),
                "null 基线不应被写进记忆");

        // 反向对照：同一夹具喂一条基线，记忆里就必须有它。
        // 没有这条，"记忆是空的"可能被"记忆压根没建起来"骗过去。
        Message baseline = new UserMessage("历史问题");
        var withBaseline = factory().create(
                command(ChatMode.KNOWLEDGE, MemoryMode.SERVER, null),
                candidate(false),
                java.util.List.of(baseline));

        List<Message> stored = withBaseline.memory().get(memoryConversationId());
        assertEquals(1, stored.size());
        assertEquals("历史问题", stored.getFirst().getText());
    }

    @Test
    void statelessModeBuildsNoMemory() {
        var attempt = factory().create(
                command(ChatMode.KNOWLEDGE, MemoryMode.STATELESS, null),
                candidate(false),
                java.util.List.of(new UserMessage("历史问题")));

        assertNull(attempt.memory(),
                "STATELESS 不建记忆，且基线不得被保留在 attempt 上");
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
