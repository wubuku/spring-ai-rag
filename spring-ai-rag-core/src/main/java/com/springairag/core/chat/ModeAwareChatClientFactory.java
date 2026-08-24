package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.AdvisorScope;
import com.springairag.api.service.RagAdvisorProvider;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.rag.CitationQueryAugmenter;
import com.springairag.core.rag.BoundedMultiQueryExpander;
import com.springairag.core.rag.HistoryAwareQueryTransformer;
import com.springairag.core.rag.ProjectDocumentRetriever;
import com.springairag.core.rag.ProjectRerankPostProcessor;
import com.springairag.core.rag.PromptBudgetDocumentPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 为每个模型候选和请求创建隔离的 ChatClient、Memory 与检索上下文。
 */
@Component
public class ModeAwareChatClientFactory {

    private static final Logger log =
            LoggerFactory.getLogger(ModeAwareChatClientFactory.class);

    private static final String EXACT_SEARCH_QUERY_PROMPT = """
            You are an information-retrieval query expansion component.
            Generate exactly {number} search query variants for the original query.
            Output one variant per line and output nothing else.

            Preserve the user's search intent. Exact terms are important: never
            replace, translate, normalize away, or omit product names, proper
            nouns, quoted phrases, identifiers, model numbers, codes, or unusual
            wording from the original query.
            At least one variant must be an exact lexical-search query that repeats
            the important terms verbatim and removes only conversational instructions
            such as "find", "search", or "related content".
            Other variants may add concise semantic context, but must retain all
            important exact terms.

            Original query:
            {query}

            Query variants:
            """;
    private static final PromptTemplate EXACT_SEARCH_QUERY_PROMPT_TEMPLATE =
            new PromptTemplate(EXACT_SEARCH_QUERY_PROMPT);

    private static final int ATTEMPT_ADVISOR_ORDER =
            BaseAdvisor.HIGHEST_PRECEDENCE + 100;
    private static final int MEMORY_ORDER = BaseAdvisor.HIGHEST_PRECEDENCE + 200;
    private static final int MODE_ORDER = BaseAdvisor.HIGHEST_PRECEDENCE + 300;
    private static final int MODEL_CALL_ADVISOR_ORDER =
            BaseAdvisor.HIGHEST_PRECEDENCE + 400;
    private static final int CUSTOM_ADVISOR_BAND_SIZE = 100;

    private final ProjectDocumentRetriever documentRetriever;
    private final ProjectRerankPostProcessor rerankPostProcessor;
    private final CitationQueryAugmenter queryAugmenter;
    private final RagProperties ragProperties;
    private final List<RagAdvisorProvider> customAdvisorProviders;
    private final ToolCallingManager toolCallingManager;
    private final PromptBudgetDocumentPostProcessor promptBudgetDocumentPostProcessor;

    public ModeAwareChatClientFactory(
            ProjectDocumentRetriever documentRetriever,
            ProjectRerankPostProcessor rerankPostProcessor,
            CitationQueryAugmenter queryAugmenter,
            RagProperties ragProperties,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            List<RagAdvisorProvider> customAdvisorProviders,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ToolCallingManager toolCallingManager) {
        this.documentRetriever = documentRetriever;
        this.rerankPostProcessor = rerankPostProcessor;
        this.queryAugmenter = queryAugmenter;
        this.ragProperties = ragProperties;
        this.customAdvisorProviders = customAdvisorProviders != null
                ? List.copyOf(customAdvisorProviders)
                : List.of();
        this.toolCallingManager = toolCallingManager != null
                ? new BudgetedToolCallingManager(
                        toolCallingManager,
                        ragProperties.getChat().getAgent()
                                .getMaxToolResultCharacters())
                : new BudgetedToolCallingManager(
                        ToolCallingManager.builder().build(),
                        ragProperties.getChat().getAgent()
                                .getMaxToolResultCharacters());
        this.promptBudgetDocumentPostProcessor =
                new PromptBudgetDocumentPostProcessor(
                        new JTokkitPromptTokenEstimator(),
                        ragProperties.getChat());
    }

    public Attempt create(
            ChatCommand command,
            ChatModelRouter.ChatModelCandidate candidate,
            List<Message> baselineMessages) {
        RagChatProperties.AgentProperties agent = ragProperties.getChat().getAgent();
        RagChatProperties.KnowledgeProperties knowledge =
                ragProperties.getChat().getKnowledge();
        ChatExecutionBudget budget = command.executionBudget();
        org.springframework.ai.chat.model.ChatModel executionModel =
                budget != null
                        ? budgetedModel(candidate, budget)
                        : candidate.model();
        RetrievalOptions options = command.mode() == ChatMode.AGENT
                ? capAgentOptions(command.retrievalOptions(), agent)
                : command.retrievalOptions();
        String attemptKey = candidate.ref() + ":" + System.nanoTime();
        RetrievalTraceCollector trace = command.retrievalTraceSession() != null
                ? command.retrievalTraceSession().newAttemptCollector(
                        attemptKey,
                        command.mode() == ChatMode.KNOWLEDGE
                                ? knowledge.getMaxRetrievalQueries()
                                : agent.getMaxRetrievalCalls(),
                        agent.getMaxToolRounds(),
                        agent.getMaxUniqueSources())
                : new RetrievalTraceCollector(
                        command.mode() == ChatMode.KNOWLEDGE
                                ? knowledge.getMaxRetrievalQueries()
                                : agent.getMaxRetrievalCalls(),
                        agent.getMaxToolRounds(),
                        agent.getMaxUniqueSources());
        if (command.mode() == ChatMode.KNOWLEDGE
                && "spring-ai".equalsIgnoreCase(
                        knowledge.getQueryTransformer())) {
            trace.configureQueryExpansion(
                    knowledge.getQueryExpanderVariants(),
                    knowledge.getEffectiveQueryExpanderVariants(),
                    knowledge.isQueryExpanderIncludeOriginal(),
                    knowledge.getMaxRetrievalQueries(),
                    knowledge.getPlannedRetrievalQueries(),
                    knowledge.isQueryExpansionBudgetLimited());
        }
        AuthorizedRetrievalContext retrievalContext =
                new AuthorizedRetrievalContext(
                        command.retrievalScope(),
                        options,
                        trace,
                        command.sessionId(),
                        command.principal(),
                        agent.getMaxToolResultCharacters(),
                        command.retrievalFilters(),
                        budget);

        List<Advisor> advisors = new ArrayList<>();
        advisors.addAll(customAdvisors(
                command.mode(),
                AdvisorScope.ATTEMPT,
                ATTEMPT_ADVISOR_ORDER));

        ChatMemory memory = null;
        if (command.memoryMode() == MemoryMode.SERVER) {
            memory = buildRequestLocalMemory(
                    command.memoryConversationId(),
                    baselineMessages,
                    command.inputMessages().size());
            advisors.add(MessageChatMemoryAdvisor.builder(memory)
                    .order(MEMORY_ORDER)
                    .build());
        }

        if (command.mode() == ChatMode.KNOWLEDGE) {
            advisors.add(buildKnowledgeAdvisor(candidate, budget));
        } else if (command.mode() == ChatMode.AGENT) {
            ensureToolOptions(candidate);
            advisors.add(new BudgetedToolCallAdvisor(
                    toolCallingManager,
                    MODE_ORDER));
        }
        advisors.addAll(customAdvisors(
                command.mode(),
                AdvisorScope.MODEL_CALL,
                MODEL_CALL_ADVISOR_ORDER));

        ChatClient.Builder builder = ChatClient.builder(executionModel)
                .defaultAdvisors(advisors);
        ChatOptions defaultOptions = candidate.model().getDefaultOptions();
        if (defaultOptions != null) {
            builder.defaultOptions(defaultOptions.copy());
        }
        return new Attempt(
                builder.build(),
                candidate,
                retrievalContext,
                memory);
    }

    private RetrievalAugmentationAdvisor buildKnowledgeAdvisor(
            ChatModelRouter.ChatModelCandidate candidate,
            ChatExecutionBudget budget) {
        RetrievalAugmentationAdvisor.Builder builder =
                RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                        .documentPostProcessors(
                                rerankPostProcessor,
                                promptBudgetDocumentPostProcessor)
                        .queryAugmenter(queryAugmenter)
                        .order(MODE_ORDER);
        QueryTransformer transformer = buildQueryTransformer(candidate, budget);
        if (transformer != null) {
            builder.queryTransformers(transformer);
        }
        QueryExpander expander = buildQueryExpander(candidate, budget);
        if (expander != null) {
            builder.queryExpander(expander);
        }
        return builder.build();
    }

    private QueryTransformer buildQueryTransformer(
            ChatModelRouter.ChatModelCandidate candidate,
            ChatExecutionBudget budget) {
        RagChatProperties.KnowledgeProperties knowledge =
                ragProperties.getChat().getKnowledge();
        if (!"spring-ai".equalsIgnoreCase(knowledge.getQueryTransformer())) {
            return null;
        }
        ChatClient.Builder rawBuilder = ChatClient.builder(
                budget != null
                        ? budgetedModel(candidate, budget)
                        : candidate.model());
        ChatOptions options = candidate.model().getDefaultOptions();
        if (options != null) {
            rawBuilder.defaultOptions(options.copy());
        }
        QueryTransformer compression = CompressionQueryTransformer.builder()
                .chatClientBuilder(rawBuilder.clone())
                .build();
        return new HistoryAwareQueryTransformer(
                null,
                compression,
                Duration.ofSeconds(
                        Math.max(1, knowledge.getQueryTransformTimeoutSeconds())));
    }

    private QueryExpander buildQueryExpander(
            ChatModelRouter.ChatModelCandidate candidate,
            ChatExecutionBudget budget) {
        RagChatProperties.KnowledgeProperties knowledge =
                ragProperties.getChat().getKnowledge();
        if (!"spring-ai".equalsIgnoreCase(knowledge.getQueryTransformer())) {
            return null;
        }
        int effectiveVariants = knowledge.getEffectiveQueryExpanderVariants();
        if (effectiveVariants <= 0) {
            return null;
        }
        ChatClient.Builder rawBuilder = ChatClient.builder(
                budget != null
                        ? budgetedModel(candidate, budget)
                        : candidate.model());
        ChatOptions options = candidate.model().getDefaultOptions();
        if (options != null) {
            rawBuilder.defaultOptions(options.copy());
        }
        MultiQueryExpander delegate = MultiQueryExpander.builder()
                .chatClientBuilder(rawBuilder)
                .promptTemplate(EXACT_SEARCH_QUERY_PROMPT_TEMPLATE)
                .includeOriginal(knowledge.isQueryExpanderIncludeOriginal())
                .numberOfQueries(effectiveVariants)
                .build();
        return new BoundedMultiQueryExpander(
                delegate,
                knowledge.getPlannedRetrievalQueries(),
                knowledge.isQueryExpanderIncludeOriginal());
    }

    private List<Advisor> customAdvisors(
            ChatMode mode,
            AdvisorScope scope,
            int firstOrder) {
        List<RagAdvisorProvider> providers = customAdvisorProviders.stream()
                .filter(provider -> {
                    if (provider.getName() == null
                            || provider.getName().isBlank()) {
                        throw new IllegalStateException(
                                "RagAdvisorProvider name must not be blank: "
                                        + provider.getClass().getName());
                    }
                    if (provider.supportedModes() == null) {
                        throw new IllegalStateException(
                                "RagAdvisorProvider '" + provider.getName()
                                        + "' returned null supportedModes");
                    }
                    if (provider.advisorScope() == null) {
                        throw new IllegalStateException(
                                "RagAdvisorProvider '" + provider.getName()
                                        + "' returned null advisorScope");
                    }
                    return provider.supportedModes().contains(mode)
                            && provider.advisorScope() == scope;
                })
                .sorted(Comparator
                        .comparingInt(RagAdvisorProvider::getOrder)
                        .thenComparing(RagAdvisorProvider::getName)
                        .thenComparing(provider ->
                                provider.getClass().getName()))
                .toList();
        if (providers.size() > CUSTOM_ADVISOR_BAND_SIZE) {
            throw new IllegalStateException(
                    "Too many " + scope + " RagAdvisorProvider instances for mode "
                            + mode + ": " + providers.size()
                            + " (maximum " + CUSTOM_ADVISOR_BAND_SIZE + ")");
        }

        List<Advisor> advisors = new ArrayList<>();
        for (int index = 0; index < providers.size(); index++) {
            RagAdvisorProvider provider = providers.get(index);
            BaseAdvisor advisor = provider.createAdvisor();
            if (advisor == null) {
                log.warn("自定义 Advisor provider {} 返回 null，已忽略",
                        provider.getName());
                continue;
            }
            advisors.add(new OrderedAdvisorAdapter(
                    advisor,
                    provider.getName(),
                    firstOrder + index));
        }
        return List.copyOf(advisors);
    }

    private ChatMemory buildRequestLocalMemory(
            String conversationId,
            List<Message> baselineMessages,
            int inputMessageCount) {
        int selectedMessageCount = baselineMessages != null
                ? baselineMessages.size()
                : 0;
        int currentTurnCapacity = Math.max(2, inputMessageCount + 1);
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(Math.max(
                        2,
                        selectedMessageCount + currentTurnCapacity))
                .build();
        if (baselineMessages != null && !baselineMessages.isEmpty()) {
            memory.add(conversationId, baselineMessages);
        }
        return memory;
    }

    private RetrievalOptions capAgentOptions(
            RetrievalOptions options,
            RagChatProperties.AgentProperties agent) {
        return new RetrievalOptions(
                Math.min(options.maxResults(),
                        Math.max(1, agent.getMaxResultsPerCall())),
                options.minScore(),
                options.useHybridSearch(),
                options.useRerank(),
                options.vectorWeight(),
                options.fulltextWeight());
    }

    private void ensureToolOptions(
            ChatModelRouter.ChatModelCandidate candidate) {
        if (!(candidate.model().getDefaultOptions()
                instanceof ToolCallingChatOptions)) {
            throw new IllegalArgumentException(
                    "Model '" + candidate.ref()
                            + "' declares tool calling but its Spring AI adapter "
                            + "does not expose ToolCallingChatOptions");
        }
    }

    private BudgetedChatModel budgetedModel(
            ChatModelRouter.ChatModelCandidate candidate,
            ChatExecutionBudget budget) {
        RagChatProperties.ContextProperties context =
                ragProperties.getChat().getContext();
        int contextWindow = candidate.contextWindow() != null
                ? candidate.contextWindow()
                : context.getFallbackContextWindow();
        int outputReserve = candidate.maxTokens() != null
                ? Math.min(context.getOutputReserveTokens(),
                        Math.max(1, candidate.maxTokens()))
                : context.getOutputReserveTokens();
        return new BudgetedChatModel(
                candidate.model(),
                budget,
                contextWindow,
                outputReserve,
                context.getSafetyMarginTokens(),
                context.getMaxToolSchemaTokens(),
                new JTokkitPromptTokenEstimator());
    }

    public record Attempt(
            ChatClient client,
            ChatModelRouter.ChatModelCandidate candidate,
            AuthorizedRetrievalContext retrievalContext,
            ChatMemory memory) {
    }
}
