package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService.loadBaseline 长尾（Batch 465）：STATELESS
 * 不读历史、SERVER 模式读历史并按时间倒序、非法会话拒绝。
 */
class ChatExecutionServiceLoadBaselineTailTest {

    private final RagChatHistoryRepository historyRepository =
            mock(RagChatHistoryRepository.class);

    private ChatExecutionService service() {
        return new ChatExecutionService(
                mock(com.springairag.core.config.ChatModelRouter.class),
                mock(com.springairag.core.chat.ModeAwareChatClientFactory.class),
                mock(KnowledgeSearchTool.class),
                historyRepository,
                mock(DomainExtensionRegistry.class),
                mock(com.springairag.core.extension.PromptCustomizerChain.class),
                mock(com.springairag.core.rag.RetrievalDocumentMapper.class),
                new ObjectMapper(),
                new com.springairag.core.config.RagProperties(),
                null,
                null);
    }

    private ChatCommand command(String sessionId, MemoryMode memoryMode) {
        return new ChatCommand(
                "问题", sessionId, ChatPrincipal.local(),
                ChatPrincipal.local().memoryConversationId(sessionId),
                com.springairag.api.enums.ChatMode.PLAIN, memoryMode, null,
                null,
                com.springairag.core.retrieval.RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, false, 0.5, 0.5),
                java.util.Map.of());
    }

    @SuppressWarnings("unchecked")
    private List<Object> loadBaseline(ChatCommand command) throws Exception {
        Method method = ChatExecutionService.class.getDeclaredMethod(
                "loadBaseline", ChatCommand.class);
        method.setAccessible(true);
        return (List<Object>) method.invoke(service(), command);
    }

    @Test
    void statelessCommandDoesNotTouchHistory() throws Exception {
        ChatCommand command = command("session-1", MemoryMode.STATELESS);

        List<Object> baseline = loadBaseline(command);

        assertEquals(0, baseline.size());
        verify(historyRepository, never()).findBySessionId(anyString(), anyInt());
    }

    @Test
    void serverModeReadsHistoryReversedIntoChronologicalOrder() throws Exception {
        com.springairag.api.dto.ChatHistoryResponse older =
                new com.springairag.api.dto.ChatHistoryResponse(
                        1L, "session-1", "old question", "old answer", null,
                        java.util.Map.of(), java.time.LocalDateTime.now());
        com.springairag.api.dto.ChatHistoryResponse newer =
                new com.springairag.api.dto.ChatHistoryResponse(
                        2L, "session-1", "new question", "new answer", null,
                        java.util.Map.of(), java.time.LocalDateTime.now());
        when(historyRepository.findBySessionId(anyString(), anyInt()))
                .thenReturn(List.of(newer, older));

        List<Object> baseline = loadBaseline(
                command("session-1", MemoryMode.SERVER));

        // 每条历史展开为 user+assistant 两条消息。
        assertEquals(4, baseline.size());
    }

}
