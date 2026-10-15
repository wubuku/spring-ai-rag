package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagProperties;
import com.springairag.core.evaluation.CitationValidator;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatExecutionServiceEvidenceSafetyIntegrationTest {

    private ChatModelRouter modelRouter;
    private ModeAwareChatClientFactory clientFactory;
    private RagChatHistoryRepository historyRepository;
    private RetrievalDocumentMapper documentMapper;
    private ChatModelRouter.ChatModelCandidate candidate;
    private ChatExecutionService service;

    @BeforeEach
    void setUp() {
        modelRouter = mock(ChatModelRouter.class);
        clientFactory = mock(ModeAwareChatClientFactory.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        documentMapper = mock(RetrievalDocumentMapper.class);
        when(historyRepository.findBySessionId(anyString(), any(Integer.class)))
                .thenReturn(List.of());
        when(documentMapper.toChatSource(any(Document.class), anyInt()))
                .thenAnswer(invocation -> source("S1"));
        when(documentMapper.toRetrievalResult(any(Document.class)))
                .thenReturn(null);
        PromptCustomizerChain promptCustomizers = mock(PromptCustomizerChain.class);
        when(promptCustomizers.hasCustomizers()).thenReturn(false);
        service = new ChatExecutionService(
                modelRouter,
                clientFactory,
                mock(KnowledgeSearchTool.class),
                historyRepository,
                mock(DomainExtensionRegistry.class),
                promptCustomizers,
                documentMapper,
                new ObjectMapper().findAndRegisterModules(),
                new RagProperties(),
                null,
                null);
        service.setCitationValidator(new CitationValidator());
        candidate = candidate("provider/model");
        when(modelRouter.orderedCandidateDescriptors(isNull()))
                .thenReturn(List.of(candidate));
    }

    @Test
    void supportedKnowledgeAnswerPassesThroughWithAssessment() {
        AuthorizedRetrievalContext context = retrievalContext(true);
        stubExecuteClient(context, "[S1] 有证据回答", documents());

        ChatExecutionResult result = service.execute(command(ChatMode.KNOWLEDGE));

        assertEquals("[S1] 有证据回答", result.answer());
        assertEquals(1, result.sources().size());
        assertEquals("SUPPORTED", result.evidenceAssessment().status());
        assertFalse(result.evidenceAssessment().safeAnswerApplied());
    }

    @Test
    void knowledgeWithoutEvidenceReturnsSafeAnswer() {
        AuthorizedRetrievalContext context = retrievalContext(false);
        stubExecuteClient(context, "没有依据的模型回答", Map.of());

        ChatExecutionResult result = service.execute(command(ChatMode.KNOWLEDGE));

        assertEquals(safeAnswer(), result.answer());
        assertEquals("NO_EVIDENCE", result.evidenceAssessment().status());
        assertTrue(result.evidenceAssessment().safeAnswerApplied());
    }

    @Test
    void invalidKnowledgeCitationReturnsSafeAnswer() {
        AuthorizedRetrievalContext context = retrievalContext(true);
        stubExecuteClient(context, "[S9] 错误引用回答", documents());

        ChatExecutionResult result = service.execute(command(ChatMode.KNOWLEDGE));

        assertEquals(safeAnswer(), result.answer());
        assertEquals("INVALID_CITATION", result.evidenceAssessment().status());
        assertTrue(result.evidenceAssessment().safeAnswerApplied());
    }

    @Test
    void plainAnswerRemainsUnchangedWithoutEvidence() {
        AuthorizedRetrievalContext context = retrievalContext(false);
        stubExecuteClient(context, "普通模式回答", Map.of());

        ChatExecutionResult result = service.execute(command(ChatMode.PLAIN));

        assertEquals("普通模式回答", result.answer());
        assertEquals("NOT_APPLICABLE", result.evidenceAssessment().status());
        assertFalse(result.evidenceAssessment().safeAnswerApplied());
    }

    @Test
    void knowledgeStreamDoesNotLeakAnswerBeforeSafetyAssessment() {
        AuthorizedRetrievalContext context = retrievalContext(false);
        ChatClient client = streamClient("不应泄漏的原始回答", Map.of());
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, candidate, context, null));

        List<ChatEvent> events = service.stream(command(ChatMode.KNOWLEDGE))
                .collectList()
                .block();

        assertTrue(events != null);
        assertTrue(events.stream().anyMatch(event ->
                event instanceof ChatEvent.ContentDelta delta
                        && safeAnswer().equals(delta.content())));
        assertTrue(events.stream().noneMatch(event ->
                event instanceof ChatEvent.ContentDelta delta
                        && "不应泄漏的原始回答".equals(delta.content())));
        ChatEvent.Completed completed = events.stream()
                .filter(ChatEvent.Completed.class::isInstance)
                .map(ChatEvent.Completed.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("NO_EVIDENCE",
                ((com.springairag.api.dto.EvidenceAssessment)
                        completed.metadata().get("evidenceAssessment")).status());
    }

    private ChatCommand command(ChatMode mode) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题",
                "session-1",
                principal,
                principal.memoryConversationId("session-1"),
                mode,
                MemoryMode.SERVER,
                null,
                null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of());
    }

    private AuthorizedRetrievalContext retrievalContext(boolean withEvidence) {
        AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                new RetrievalTraceCollector(),
                "session-1",
                ChatPrincipal.local());
        if (withEvidence) {
            context.trace().tryBeginRetrieval("问题");
            RetrievalResult result = new RetrievalResult();
            result.setDocumentId("10");
            result.setChunkIndex(1);
            result.setChunkText("证据");
            context.trace().record(List.of(result));
        }
        return context;
    }

    private void stubExecuteClient(
            AuthorizedRetrievalContext context,
            String answer,
            Map<String, Object> responseContext) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        ChatClientResponse response = response(answer, responseContext);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.chatClientResponse()).thenReturn(response);
        when(clientFactory.create(any(), same(candidate), anyList()))
                .thenReturn(new ModeAwareChatClientFactory.Attempt(
                        client, candidate, context, null));
    }

    private ChatClient streamClient(String answer, Map<String, Object> responseContext) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream =
                mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.toolCallbacks(any(KnowledgeSearchTool.class))).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.options(any(ToolCallingChatOptions.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatClientResponse()).thenReturn(
                Flux.just(response(answer, responseContext)));
        return client;
    }

    private ChatClientResponse response(
            String answer,
            Map<String, Object> responseContext) {
        return new ChatClientResponse(
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage(answer))),
                        ChatResponseMetadata.builder().build()),
                responseContext);
    }

    private Map<String, Object> documents() {
        return Map.of(
                RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT,
                List.of(Document.builder()
                        .id("10:1")
                        .text("证据")
                        .metadata(Map.of(
                                "documentId", "10",
                                "chunkIndex", 1,
                                "title", "资料"))
                        .build()));
    }

    private ChatSource source(String citationId) {
        ChatSource source = new ChatSource();
        source.setCitationId(citationId);
        source.setDocumentId("10");
        return source;
    }

    private ChatModelRouter.ChatModelCandidate candidate(String ref) {
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(
                mock(ToolCallingChatOptions.class));
        return new ChatModelRouter.ChatModelCandidate(
                ref,
                model,
                com.springairag.core.config.MultiModelProperties
                        .ModelCapabilities.defaults());
    }

    private String safeAnswer() {
        return new RagProperties().getChat().getKnowledge().getSafeAnswerText();
    }
}
