package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.AdvisorScope;
import com.springairag.api.service.RagAdvisorProvider;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.MultiModelProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.rag.CitationQueryAugmenter;
import com.springairag.core.rag.ProjectDocumentRetriever;
import com.springairag.core.rag.ProjectRerankPostProcessor;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModeAwareChatClientFactoryTest {

    private ProjectDocumentRetriever documentRetriever;
    private ProjectRerankPostProcessor rerankPostProcessor;
    private ModeAwareChatClientFactory factory;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        properties.getChat().getKnowledge().setQueryTransformer("none");
        documentRetriever = mock(ProjectDocumentRetriever.class);
        rerankPostProcessor = mock(ProjectRerankPostProcessor.class);
        when(rerankPostProcessor.process(any(Query.class), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        factory = new ModeAwareChatClientFactory(
                documentRetriever,
                rerankPostProcessor,
                new CitationQueryAugmenter(properties),
                properties,
                List.of(),
                mock(ToolCallingManager.class));
    }

    @Test
    void knowledgeModeRunsSpringAiModularRagAndPreservesDocumentContext() {
        ChatModel model = model("answer");
        Document document = Document.builder()
                .id("20:1")
                .text("风格基调应保持克制和清晰。")
                .metadata(Map.of(
                        "documentId", "20",
                        "chunkIndex", 1,
                        "title", "品牌规范",
                        "score", 0.91))
                .build();
        when(documentRetriever.retrieve(any(Query.class)))
                .thenReturn(List.of(document));
        ChatCommand command = command(ChatMode.KNOWLEDGE);
        ModeAwareChatClientFactory.Attempt attempt = factory.create(
                command,
                candidate("knowledge", model),
                List.of());

        ChatClientResponse response = attempt.client().prompt()
                .user(command.message())
                .advisors(advisor -> advisor.param(
                        ProjectDocumentRetriever.CONTEXT_KEY,
                        attempt.retrievalContext()))
                .call()
                .chatClientResponse();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertTrue(prompt.getValue().getContents().contains("[S1]"));
        assertTrue(prompt.getValue().getContents().contains("风格基调应保持克制和清晰"));
        assertTrue(prompt.getValue().getContents().contains("用户问题"));
        assertEquals(
                List.of(document),
                response.context().get(
                        org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor
                                .DOCUMENT_CONTEXT));
        verify(documentRetriever).retrieve(any(Query.class));
        verify(rerankPostProcessor).process(any(Query.class), anyList());
    }

    @Test
    void plainModeDoesNotRunRetrievalAdvisor() {
        ChatModel model = model("plain answer");
        ChatCommand command = command(ChatMode.PLAIN);
        ModeAwareChatClientFactory.Attempt attempt = factory.create(
                command,
                candidate("plain", model),
                List.of());

        ChatClientResponse response = attempt.client().prompt()
                .user(command.message())
                .call()
                .chatClientResponse();

        assertEquals(
                "plain answer",
                response.chatResponse().getResult().getOutput().getText());
        verify(documentRetriever, never()).retrieve(any(Query.class));
        verify(rerankPostProcessor, never())
                .process(any(Query.class), anyList());
    }

    @Test
    void advisorScopesUseStableBandsAndModelCallRunsInsideToolLoop() {
        AtomicInteger attemptBefore = new AtomicInteger();
        AtomicInteger attemptAfter = new AtomicInteger();
        AtomicInteger modelBefore = new AtomicInteger();
        AtomicInteger modelAfter = new AtomicInteger();
        ToolCallingManager manager = mock(ToolCallingManager.class);
        RagAdvisorProvider attemptProvider = provider(
                "attempt", 900, AdvisorScope.ATTEMPT,
                attemptBefore, attemptAfter);
        RagAdvisorProvider modelProvider = provider(
                "model", -500, AdvisorScope.MODEL_CALL,
                modelBefore, modelAfter);
        RagProperties properties = new RagProperties();
        properties.getChat().getKnowledge().setQueryTransformer("none");
        ModeAwareChatClientFactory scopedFactory =
                new ModeAwareChatClientFactory(
                        documentRetriever,
                        rerankPostProcessor,
                        new CitationQueryAugmenter(properties),
                        properties,
                        List.of(modelProvider, attemptProvider),
                        manager);
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(
                ToolCallingChatOptions.builder().model("tool-model").build());
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (modelCalls.incrementAndGet() == 1) {
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "searchKnowledge",
                                "{\"query\":\"风格基调\"}")))
                        .build();
                return new ChatResponse(
                        List.of(new Generation(toolCall)),
                        ChatResponseMetadata.builder().build());
            }
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("final answer"))),
                    ChatResponseMetadata.builder().build());
        });
        when(manager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenAnswer(invocation -> {
                    ChatResponse response = invocation.getArgument(1);
                    ToolResponseMessage toolResponse =
                            ToolResponseMessage.builder()
                                    .responses(List.of(
                                            new ToolResponseMessage.ToolResponse(
                                                    "call-1",
                                                    "searchKnowledge",
                                                    "{\"resultCount\":0}")))
                                    .build();
                    return ToolExecutionResult.builder()
                            .conversationHistory(List.of(
                                    new UserMessage("风格基调是什么？"),
                                    response.getResult().getOutput(),
                                    toolResponse))
                            .build();
                });
        ChatModelRouter.ChatModelCandidate candidate =
                new ChatModelRouter.ChatModelCandidate(
                        "agent",
                        model,
                        new MultiModelProperties.ModelCapabilities(true, true));
        ChatCommand command = command(ChatMode.AGENT);
        ModeAwareChatClientFactory.Attempt attempt = scopedFactory.create(
                command, candidate, List.of());

        ChatClientResponse response = attempt.client().prompt()
                .user(command.message())
                .advisors(advisor -> advisor.param(
                        ProjectDocumentRetriever.CONTEXT_KEY,
                        attempt.retrievalContext()))
                .call()
                .chatClientResponse();

        assertEquals(
                "final answer",
                response.chatResponse().getResult().getOutput().getText());
        assertEquals(2, modelCalls.get());
        assertEquals(1, attemptBefore.get());
        assertEquals(1, attemptAfter.get());
        assertEquals(2, modelBefore.get());
        assertEquals(2, modelAfter.get());
    }

    private RagAdvisorProvider provider(
            String name,
            int declaredOrder,
            AdvisorScope scope,
            AtomicInteger beforeCount,
            AtomicInteger afterCount) {
        return new RagAdvisorProvider() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getOrder() {
                return declaredOrder;
            }

            @Override
            public BaseAdvisor createAdvisor() {
                return new BaseAdvisor() {
                    @Override
                    public ChatClientRequest before(
                            ChatClientRequest request,
                            AdvisorChain advisorChain) {
                        beforeCount.incrementAndGet();
                        return request;
                    }

                    @Override
                    public ChatClientResponse after(
                            ChatClientResponse response,
                            AdvisorChain advisorChain) {
                        afterCount.incrementAndGet();
                        return response;
                    }

                    @Override
                    public int getOrder() {
                        return declaredOrder;
                    }
                };
            }

            @Override
            public Set<ChatMode> supportedModes() {
                return Set.of(ChatMode.AGENT);
            }

            @Override
            public AdvisorScope advisorScope() {
                return scope;
            }
        };
    }

    private ChatCommand command(ChatMode mode) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "风格基调是什么？",
                "session-factory",
                principal,
                principal.memoryConversationId("session-factory"),
                mode,
                MemoryMode.STATELESS,
                null,
                null,
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of());
    }

    private ChatModelRouter.ChatModelCandidate candidate(
            String ref,
            ChatModel model) {
        return new ChatModelRouter.ChatModelCandidate(
                ref,
                model,
                new MultiModelProperties.ModelCapabilities(true, false));
    }

    private ChatModel model(String answer) {
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(
                ChatOptions.builder().model("test-model").build());
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage(answer))),
                        ChatResponseMetadata.builder().build()));
        return model;
    }
}
