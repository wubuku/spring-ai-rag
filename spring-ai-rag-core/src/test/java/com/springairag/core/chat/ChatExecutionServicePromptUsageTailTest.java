package com.springairag.core.chat;

import com.springairag.core.chat.ChatCommand;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.skill.RuntimeSkillCatalog;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.extension.PromptCustomizerChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService 系统提示词与用量长尾（Batch 464）：
 * buildSystemPrompt 的三模式分支、domain 模板拼接、Skill 目录追
 * 加；usage 的 null/部分字段归一。
 */
class ChatExecutionServicePromptUsageTailTest {

    private DomainExtensionRegistry domainExtensions;
    private RuntimeSkillCatalog skillCatalog;
    private ChatExecutionService service;
    private Method buildSystemPrompt;
    private Method usage;

    @BeforeEach
    void setUp() throws Exception {
        domainExtensions = mock(DomainExtensionRegistry.class);
        skillCatalog = mock(RuntimeSkillCatalog.class);
        service = new ChatExecutionService(
                mock(ChatModelRouter.class),
                mock(ModeAwareChatClientFactory.class),
                mock(KnowledgeSearchTool.class),
                mock(RagChatHistoryRepository.class),
                domainExtensions,
                mock(PromptCustomizerChain.class),
                mock(RetrievalDocumentMapper.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.springairag.core.config.RagProperties(),
                null,
                null);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "runtimeSkillCatalog", skillCatalog);
        buildSystemPrompt = ChatExecutionService.class.getDeclaredMethod(
                "buildSystemPrompt", ChatCommand.class);
        buildSystemPrompt.setAccessible(true);
        usage = ChatExecutionService.class.getDeclaredMethod(
                "usage", org.springframework.ai.chat.metadata.Usage.class);
        usage.setAccessible(true);
    }

    private String systemPrompt(ChatCommand command) throws Exception {
        return (String) buildSystemPrompt.invoke(service, command);
    }

    private ChatCommand plainCommand(String domainId) {
        return new ChatCommand(
                "问题", "session-1", ChatPrincipal.local(),
                ChatPrincipal.local().memoryConversationId("session-1"),
                ChatMode.PLAIN, MemoryMode.STATELESS, null, domainId,
                com.springairag.core.retrieval.RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, false, 0.5, 0.5),
                java.util.Map.of());
    }

    private ChatCommand agentCommand(String domainId) {
        return new ChatCommand(
                "问题", "session-1", ChatPrincipal.local(),
                ChatPrincipal.local().memoryConversationId("session-1"),
                ChatMode.AGENT, MemoryMode.STATELESS, null, domainId,
                com.springairag.core.retrieval.RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, false, 0.5, 0.5),
                java.util.Map.of());
    }

    @Test
    void systemPromptDiffersPerMode() throws Exception {
        assertTrue(systemPrompt(plainCommand(null))
                .contains("通用 AI 助手"));
        assertTrue(systemPrompt(agentCommand(null))
                .contains("知识探索助手"));
        assertTrue(systemPrompt(plainCommand(null)).length() > 0);
    }

    @Test
    void domainPromptIsPrependedWhenConfigured() throws Exception {
        when(domainExtensions.hasDomain("legal")).thenReturn(true);
        when(domainExtensions.getSystemPromptTemplate("legal", ChatMode.PLAIN))
                .thenReturn("法务领域专用提示");

        String prompt = systemPrompt(plainCommand("legal"));

        assertTrue(prompt.startsWith("法务领域专用提示"));
    }

    @Test
    void skillCatalogAppendsLevelOnePromptForAgentMode() throws Exception {
        when(skillCatalog.enabled()).thenReturn(true);
        when(skillCatalog.levelOnePrompt(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("- weather: 查询天气");

        String prompt = systemPrompt(agentCommand(null));

        assertTrue(prompt.contains("- weather: 查询天气"));
    }

    @Test
    void systemPromptDefaultsPerModeWithoutDomain() throws Exception {
        String knowledge = systemPrompt(
                commandWithMode(com.springairag.api.enums.ChatMode.KNOWLEDGE));
        assertTrue(knowledge.contains("知识库问答助手"));
        String agent = systemPrompt(
                commandWithMode(com.springairag.api.enums.ChatMode.AGENT));
        assertTrue(agent.contains("知识探索助手"));
    }

    private ChatCommand commandWithMode(com.springairag.api.enums.ChatMode mode) {
        return new ChatCommand(
                "问题", "session-1", ChatPrincipal.local(),
                ChatPrincipal.local().memoryConversationId("session-1"),
                mode, MemoryMode.STATELESS, null, null,
                com.springairag.core.retrieval.RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(
                        5, 0.25, true, false, 0.5, 0.5),
                java.util.Map.of());
    }

    @Test
    void usageOmitsNullTokenFields() throws Exception {
        Usage nullUsage = null;
        assertEquals(Map.of(), usage.invoke(service, nullUsage));

        var partial = mock(org.springframework.ai.chat.metadata.Usage.class);
        when(partial.getPromptTokens()).thenReturn(10);
        when(partial.getCompletionTokens()).thenReturn(null);
        when(partial.getTotalTokens()).thenReturn(25);

        Map<String, Object> usageMap = (Map<String, Object>) usage.invoke(
                service, partial);
        assertEquals(10, usageMap.get("promptTokens"));
        assertEquals(25, usageMap.get("totalTokens"));
        assertFalse(usageMap.containsKey("completionTokens"));
    }
}
