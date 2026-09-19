package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CitationValidation;
import com.springairag.core.config.RagProperties;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.rag.KnowledgeSearchTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * ChatExecutionService 纯函数长尾（Batch 539，JaCoCo 驱动）：引用
 * 校验结果映射、来源 ID 序列化、tokenCount 数值/缺失判定、
 * parseLong 三分支、plannedMessages 的输入消息组装。
 */
class ChatExecutionServiceCitationMapTailTest {

    private ChatExecutionService service;

    @BeforeEach
    void setUp() {
        service = new ChatExecutionService(
                mock(com.springairag.core.config.ChatModelRouter.class),
                mock(com.springairag.core.chat.ModeAwareChatClientFactory.class),
                mock(KnowledgeSearchTool.class),
                mock(RagChatHistoryRepository.class),
                mock(DomainExtensionRegistry.class),
                mock(PromptCustomizerChain.class),
                mock(RetrievalDocumentMapper.class),
                new ObjectMapper().findAndRegisterModules(),
                new RagProperties(),
                null,
                null);
    }

    private Object invoke(String name, Class<?>[] params, Object... args)
            throws Exception {
        var method = ChatExecutionService.class
                .getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method.invoke(service, args);
    }

    @Test
    @SuppressWarnings("unchecked")
    void citationMapExposesAllValidationDimensions() throws Exception {
        var validation = new CitationValidation(
                "VALID",
                List.of("10", "11"),
                List.of("10"),
                List.of("99"),
                1,
                2);

        var map = (Map<String, Object>) invoke("citationMap",
                new Class<?>[]{CitationValidation.class}, validation);

        assertEquals("VALID", map.get("status"));
        assertEquals(List.of("10", "11"), map.get("availableIds"));
        assertEquals(List.of("10"), map.get("citedIds"));
        assertEquals(List.of("99"), map.get("invalidIds"));
        assertEquals(1, map.get("citedSourceCount"));
        assertEquals(2, map.get("sourceCount"));
    }

    @Test
    void serializeDocumentIdsDeduplicatesAndDropsNonNumeric()
            throws Exception {
        var sources = List.of(
                source("10"), source("abc"), source("11"), source("10"));

        assertEquals("[10,11]",
                invoke("serializeDocumentIds",
                        new Class<?>[]{List.class}, sources));

        assertNull(invoke("serializeDocumentIds",
                new Class<?>[]{List.class},
                List.of(source("abc"), source(null))));
    }

    private com.springairag.api.dto.ChatSource source(String documentId) {
        var source = new com.springairag.api.dto.ChatSource();
        source.setDocumentId(documentId);
        return source;
    }

    @Test
    void tokenCountReadsTotalTokensAndDefaultsToZero() throws Exception {
        assertEquals(128, invoke("tokenCount",
                new Class<?>[]{Map.class}, Map.of("totalTokens", 128)));
        assertEquals(0, invoke("tokenCount",
                new Class<?>[]{Map.class}, Map.of("promptTokens", 5)));
    }

    @Test
    void parseLongHandlesNumericNullAndGarbage() throws Exception {
        assertEquals(42L, invoke("parseLong",
                new Class<?>[]{String.class}, "42"));
        assertNull(invoke("parseLong",
                new Class<?>[]{String.class}, (Object) null));
        assertNull(invoke("parseLong",
                new Class<?>[]{String.class}, "garbage"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void plannedMessagesPrependsSyntheticSummaryBeforeRecentTurns()
            throws Exception {
        var recent = List.<Message>of(
                new UserMessage("first question"),
                new AssistantMessage("first answer"));
        ConversationPromptPlan plan = new ConversationPromptPlan(
                8192, false, 10, 5, 6, 0, 0, 0,
                5, 4, "durable summary", recent, List.of());

        var planned = (List<Message>) invoke("plannedMessages",
                new Class<?>[]{ConversationPromptPlan.class}, plan);

        assertEquals(3, planned.size());
        // 摘要以合成 assistant 消息置于最前。
        assertEquals("durable summary", planned.get(0).getText());
        assertTrue(planned.get(1) instanceof UserMessage);
        assertEquals("first question", planned.get(1).getText());
        assertEquals("first answer", planned.get(2).getText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void plannedMessagesOmitsBlankSummary() throws Exception {
        var recent = List.<Message>of(new UserMessage("only turn"));
        ConversationPromptPlan plan = new ConversationPromptPlan(
                8192, false, 10, 5, 6, 0, 0, 0,
                0, 2, "  ", recent, List.of());

        var planned = (List<Message>) invoke("plannedMessages",
                new Class<?>[]{ConversationPromptPlan.class}, plan);

        assertEquals(1, planned.size());
        assertEquals("only turn", planned.getFirst().getText());
    }
}
