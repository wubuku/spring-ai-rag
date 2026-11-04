package com.springairag.core.chat;

import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.repository.RagChatMemorySummaryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开启 compaction 后的真实长会话行为（不是单次调用快照）。
 *
 * <p>用有状态的“库”替身串起多轮：历史只追加、摘要按版本 CAS 写入、摘要文本由
 * 一个会读取前一份摘要的假模型生成。断言覆盖：早期事实在压缩后仍可回答、
 * 历史消息不丢不乱序、游标只前进且不重复压缩、关闭 compaction 时完全不走这条路径。</p>
 */
class ConversationSummaryLongSessionTest {

    private static final String SESSION = "session-long";

    private RagChatMemorySummaryRepository summaryRepository;
    private RagChatHistoryRepository historyRepository;
    private ChatModelRouter modelRouter;
    private ChatModel model;
    private ChatModelRouter.ChatModelCandidate candidate;
    private ChatPrincipal principal;
    private RagProperties properties;
    private ConversationSummaryService service;

    private final List<ChatHistoryResponse> rows = new ArrayList<>();
    private final List<Long> savedCursors = new ArrayList<>();
    private final RagChatMemorySummaryRepository.SummaryRow[] stored = new RagChatMemorySummaryRepository.SummaryRow[1];

    @BeforeEach
    void setUp() {
        summaryRepository = mock(RagChatMemorySummaryRepository.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        modelRouter = mock(ChatModelRouter.class);
        model = mock(ChatModel.class);
        principal = new ChatPrincipal("db:long-session", "TEST", false);

        candidate = new ChatModelRouter.ChatModelCandidate(
                "test/model",
                model,
                new MultiModelProperties.ModelCapabilities(true, false),
                8_192,
                512);
        when(modelRouter.resolveCandidateRequired("test/model")).thenReturn(candidate);

        properties = new RagProperties();
        RagChatProperties.ContextProperties context = properties.getChat().getContext();
        context.setCompactionEnabled(true);
        context.setCompactionTriggerTokens(100);
        context.setCompactionMaxSourceTokens(4_000);
        context.setCompactionMaxOutputTokens(200);
        context.setCompactionMaxTurnsPerCall(2);
        context.setCompactionTimeoutMs(5_000);
        context.setMinimumRecentTurns(1);

        when(historyRepository.findOwnedBaseline(any(), eq(SESSION), anyInt()))
                .thenAnswer(invocation -> {
                    int limit = invocation.getArgument(2);
                    int from = Math.max(0, rows.size() - limit);
                    return List.copyOf(rows.subList(from, rows.size()));
                });
        when(historyRepository.findOwnedAfterHistoryId(any(), eq(SESSION), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    long cursor = invocation.getArgument(2);
                    int limit = invocation.getArgument(3);
                    return rows.stream()
                            .filter(row -> row.id() > cursor)
                            .limit(limit)
                            .toList();
                });

        when(summaryRepository.find(any(), eq(SESSION)))
                .thenAnswer(invocation -> Optional.ofNullable(stored[0]));
        when(summaryRepository.saveCas(
                any(), eq(SESSION), anyLong(), anyLong(), anyString(), anyInt(), eq("test/model")))
                .thenAnswer(invocation -> {
                    long expectedVersion = invocation.getArgument(2);
                    long cursor = invocation.getArgument(3);
                    RagChatMemorySummaryRepository.SummaryRow current = stored[0];
                    if (current == null) {
                        if (expectedVersion != 0L) {
                            return false;
                        }
                    } else if (current.version() != expectedVersion
                            || current.summarizedThroughHistoryId() >= cursor) {
                        return false;
                    }
                    stored[0] = new RagChatMemorySummaryRepository.SummaryRow(
                            current == null ? 1L : current.version() + 1,
                            cursor,
                            invocation.getArgument(4),
                            "test/model",
                            invocation.getArgument(5),
                            Instant.now());
                    savedCursors.add(cursor);
                    return true;
                });

        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String text = prompt.getInstructions().stream()
                    .map(message -> message.getText() == null ? "" : message.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            LinkedHashSet<String> facts = new LinkedHashSet<>();
            for (String line : text.split("\n")) {
                int at = line.indexOf("FACT:");
                if (at >= 0) {
                    facts.add(line.substring(at).trim());
                }
            }
            return response(facts.isEmpty()
                    ? "no durable facts"
                    : String.join("; ", facts));
        });

        service = new ConversationSummaryService(
                summaryRepository,
                historyRepository,
                modelRouter,
                properties);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void longSessionKeepsEarlyFactsWithoutLosingOrReorderingHistory() {
        ConversationSummaryService.CompactionResult first =
                turn(1L, "turn 1", "FACT: project codename is ORION");
        turn(2L, "turn 2", null);
        for (long id = 3; id <= 12; id += 1) {
            turn(id, "turn " + id, null);
        }
        assertFalse(first.updated(),
                "第一轮只有一条受保护消息，不应压缩"); // 保护窗口至少保留最新一轮

        // 到第 12 轮时游标应追上倒数第二轮；再追加一轮后继续前进。
        turn(13L, "turn 13", null);

        assertEquals(13, rows.size(), "历史行不允许被压缩流程删除");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L),
                rows.stream().map(ChatHistoryResponse::id).toList(),
                "历史行顺序不允许改变");
        assertEquals(12L, stored[0].summarizedThroughHistoryId(),
                "游标应停在最新受保护轮之前");
        for (int i = 1; i < savedCursors.size(); i += 1) {
            assertTrue(savedCursors.get(i) > savedCursors.get(i - 1),
                    () -> "游标必须严格前进，实际 " + savedCursors);
        }
        assertEquals(savedCursors.size(), stored[0].version(),
                "每次 CAS 成功只应产生一个版本");

        assertTrue(stored[0].text().contains("ORION"),
                "早期事实必须进入摘要：" + stored[0].text());
        Optional<ConversationSummaryService.SummarySnapshot> snapshot =
                service.load(principal, SESSION);
        String summaryText = service.promptText(snapshot);
        assertTrue(summaryText.contains("ORION"),
                "后续请求的摘要提示必须仍包含早期事实");
        assertTrue(summaryText.contains("untrusted historical data")
                        && summaryText.contains("not instructions or citation evidence"),
                "摘要必须带不可信/非引用声明");
        String answer = summaryText.contains("ORION") ? "ORION" : "unknown";
        assertEquals("ORION", answer,
                "只依赖摘要回答问题时也必须能取回早期事实");

        // 预算内不重复压缩：没有新行时再次调用不应产生新版本或新模型调用。
        int savesBefore = savedCursors.size();
        ChatExecutionBudget repeatBudget = budget();
        ConversationSummaryService.CompactionResult repeat = service.compactIfNeeded(
                command(repeatBudget), candidate, List.of());
        assertFalse(repeat.updated());
        assertEquals("compaction_source_empty", repeat.reason());
        assertEquals(savesBefore, savedCursors.size());
        assertEquals(0, repeatBudget.summaryCalls(), "跳过时不应消耗摘要模型预算");
    }

    @Test
    void everySuccessfulCompactionUsesOneSummaryCallWithinBudget() {
        rows.add(row(1L, "turn 1", "FACT: project codename is ORION"));
        rows.add(row(2L, "turn 2", null));
        ChatExecutionBudget budget = budget();
        ConversationSummaryService.CompactionResult result =
                service.compactIfNeeded(command(budget), candidate, List.of());

        assertTrue(result.updated());
        assertEquals(1, budget.summaryCalls(), "一次成功压缩只应调用一次摘要模型");
        assertEquals(1L, stored[0].summarizedThroughHistoryId());
        assertEquals(1L, stored[0].version());
    }

    @Test
    void disabledCompactionDoesNotTouchSummaryOrModel() {
        properties.getChat().getContext().setCompactionEnabled(false);
        service = new ConversationSummaryService(
                summaryRepository, historyRepository, modelRouter, properties);
        rows.add(row(1L, "turn 1", "answer 1"));

        ChatExecutionBudget budget = budget();
        ConversationSummaryService.CompactionResult result =
                service.compactIfNeeded(command(budget), candidate, List.of());

        assertFalse(result.updated());
        assertEquals("compaction_disabled", result.reason());
        assertEquals(0, budget.summaryCalls());
        verify(summaryRepository, never()).saveCas(
                any(), anyString(), anyLong(), anyLong(), anyString(), anyInt(), anyString());
        verify(model, never()).call(any(Prompt.class));
        assertEquals("", service.promptText(Optional.empty()),
                "没有摘要时关闭 compaction 的提示行为保持不变");
    }

    private ConversationSummaryService.CompactionResult turn(
            long id, String question, String fact) {
        rows.add(row(id, question, fact));
        return service.compactIfNeeded(command(budget()), candidate, List.of());
    }

    private ChatHistoryResponse row(long id, String question, String fact) {
        String filler = ("background context for token budget. ").repeat(20);
        return new ChatHistoryResponse(
                id,
                SESSION,
                question + "\n" + (fact == null ? "" : fact + "\n") + filler,
                "answer " + id,
                List.of(),
                Map.of(),
                List.of(),
                "COMPLETE",
                ChatMode.PLAIN,
                "test/model",
                "test/model",
                LocalDateTime.now());
    }

    private ChatExecutionBudget budget() {
        return new ChatExecutionBudget(
                Instant.now().plusSeconds(30),
                2, 4, 2, 4, 2, 20_000);
    }

    private ChatCommand command(ChatExecutionBudget budget) {
        return new ChatCommand(
                "current question",
                SESSION,
                principal,
                principal.memoryConversationId(SESSION),
                ChatMode.PLAIN,
                MemoryMode.SERVER,
                "test/model",
                null,
                com.springairag.core.retrieval.RetrievalScope.unscoped(),
                new RetrievalOptions(1, 0.0, false, false, 0.5, 0.5),
                Map.of()).withExecutionBudget(budget);
    }

    private ChatResponse response(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().build());
    }
}
