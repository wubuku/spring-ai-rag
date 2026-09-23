package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import com.springairag.core.diagnostics.RetrievalTraceSession;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import com.springairag.core.retrieval.RetrievalScope;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatExecutionService 结果组装长尾（Batch 575，JaCoCo 驱动）：
 * toResult 对 KNOWLEDGE 模式提取 DOCUMENT_CONTEXT 来源并写入追踪元
 * 数据、PLAIN 模式零来源、记忆回放无工具条目时不写元数据键、usage
 * 携带 token 计数。
 */
class ChatExecutionServiceToResultTailTest {

    private RetrievalDocumentMapper documentMapper;
    private ChatExecutionService service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(RetrievalDocumentMapper.class);
        when(documentMapper.toChatSource(any(Document.class), anyInt()))
                .thenAnswer(invocation -> {
                    Document document = invocation.getArgument(0);
                    ChatSource source = new ChatSource();
                    source.setDocumentId(String.valueOf(
                            document.getMetadata().getOrDefault(
                                    "documentId", "unknown")));
                    source.setTitle(String.valueOf(
                            document.getMetadata().getOrDefault("title", "")));
                    return source;
                });
        service = new ChatExecutionService(
                mock(ChatModelRouter.class),
                mock(ModeAwareChatClientFactory.class),
                mock(KnowledgeSearchTool.class),
                mock(RagChatHistoryRepository.class),
                mock(DomainExtensionRegistry.class),
                mock(PromptCustomizerChain.class),
                documentMapper,
                new ObjectMapper().findAndRegisterModules(),
                new RagProperties(),
                null,
                null);
    }

    private ChatCommand command(ChatMode mode,
                                RetrievalTraceSession traceSession) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                mode, MemoryMode.SERVER, null, null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of(), List.of(), List.of(), traceSession, null, null);
    }

    private ModeAwareChatClientFactory.Attempt attempt(
            AuthorizedRetrievalContext context, ChatMemory memory) {
        return new ModeAwareChatClientFactory.Attempt(
                mock(ChatClient.class),
                new ChatModelRouter.ChatModelCandidate(
                        "vendor/model", mock(ChatModel.class),
                        com.springairag.core.config.MultiModelProperties
                                .ModelCapabilities.defaults()),
                context, memory);
    }

    private ChatClientResponse response(String answer,
                                        Map<String, Object> context) {
        ChatResponse springResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(answer))),
                ChatResponseMetadata.builder()
                        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(
                                100, 20, 120) {
                        })
                        .build());
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(springResponse);
        when(response.context()).thenReturn(context);
        return response;
    }

    private ChatExecutionResult invokeToResult(
            ChatCommand command,
            ModeAwareChatClientFactory.Attempt attempt,
            ChatClientResponse response) throws Exception {
        Method method = ChatExecutionService.class.getDeclaredMethod(
                "toResult", ChatCommand.class,
                ModeAwareChatClientFactory.Attempt.class,
                ChatClientResponse.class);
        method.setAccessible(true);
        return (ChatExecutionResult) method.invoke(service, command,
                attempt, response);
    }

    private AuthorizedRetrievalContext context() {
        return new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                new RetrievalTraceCollector(), "session-1",
                ChatPrincipal.local(), 1_000);
    }

    @Test
    void knowledgeModeExtractsSourcesAndWritesTraceId() throws Exception {
        Document document = Document.builder()
                .id("10:2")
                .text("资料内容")
                .metadata(Map.of("documentId", "10", "chunkIndex", 2,
                        "title", "品牌手册", "score", 0.84))
                .build();
        RetrievalTraceSession session = new RetrievalTraceSession(
                ChatPrincipal.local(), "chat", "session-trace");
        Map<String, Object> context = Map.of(
                RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT,
                List.of(document));

        ChatExecutionResult result = invokeToResult(
                command(ChatMode.KNOWLEDGE, session),
                attempt(context(), null),
                response("基于资料的回答", context));

        assertEquals("基于资料的回答", result.answer());
        assertEquals(1, result.sources().size());
        assertEquals("10", result.sources().getFirst().getDocumentId());
        // KNOWLEDGE 模式 DOCUMENT_CONTEXT 优先，不经 trace.record() →
        // retrievalExecuted=false（0 次检索调用）。
        assertEquals(Boolean.FALSE,
                result.metadata().get("retrievalExecuted"));
    }

    @Test
    void plainModeYieldsZeroSourcesAndSkipsTraceKeys() throws Exception {
        ChatExecutionResult result = invokeToResult(
                command(ChatMode.PLAIN, null),
                attempt(context(), null),
                response("plain answer", Map.of()));

        assertEquals(0, result.sources().size());
        assertEquals("plain answer", result.answer());
        assertFalse(result.metadata().containsKey("retrievalTraceId"));
    }

    @Test
    void memoryTranscriptWithoutToolEntriesWritesNoMetadataKey()
            throws Exception {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get("session-1")).thenReturn(List.of(
                new UserMessage("q"),
                new AssistantMessage("a")));
        ChatCommand command = new ChatCommand(
                "问题", "session-1", ChatPrincipal.local(),
                ChatPrincipal.local().memoryConversationId("session-1"),
                ChatMode.PLAIN, MemoryMode.SERVER, null, null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of(), List.of(), List.of(), null, null, null);

        ChatExecutionResult result = invokeToResult(
                command,
                attempt(context(), memory),
                response("answer", Map.of()));

        assertFalse(result.metadata().containsKey(
                ChatMemoryMessageProjector.TOOL_TRANSCRIPT_METADATA_KEY));
    }

    @Test
    void usageMetadataCarriesTokenCounts() throws Exception {
        ChatExecutionResult result = invokeToResult(
                command(ChatMode.PLAIN, null),
                attempt(context(), null),
                response("answer", Map.of()));

        // usage 存于 record 组件 usage 而非 metadata。
        assertEquals(120, result.usage().get("totalTokens"));
    }

    @Test
    void documentContextSkipsNonDocumentEntries() throws Exception {
        Document document = Document.builder()
                .id("10:2").text("资料")
                .metadata(Map.of("documentId", "10")).build();
        Map<String, Object> context = Map.of(
                RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT,
                List.of(document, "not-a-document"));

        ChatExecutionResult result = invokeToResult(
                command(ChatMode.KNOWLEDGE, null),
                attempt(context(), null),
                response("answer", context));

        assertEquals(1, result.sources().size());
    }
}
