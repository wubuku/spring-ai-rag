package com.springairag.core.config;

/**
 * Chat execution configuration under {@code rag.chat}.
 *
 * <p>The class deliberately keeps chat mode policy separate from the generic
 * retrieval properties. This prevents a chat compatibility default from
 * silently changing the direct search endpoint.</p>
 */
public class RagChatProperties {

    private String defaultMode = "KNOWLEDGE";
    private KnowledgeProperties knowledge = new KnowledgeProperties();
    private AgentProperties agent = new AgentProperties();
    private HistoryProperties history = new HistoryProperties();
    private ExecutionProperties execution = new ExecutionProperties();
    private ContextProperties context = new ContextProperties();
    private IdempotencyProperties idempotency = new IdempotencyProperties();

    public String getDefaultMode() {
        return defaultMode;
    }

    public void setDefaultMode(String defaultMode) {
        this.defaultMode = defaultMode;
    }

    public KnowledgeProperties getKnowledge() {
        return knowledge;
    }

    public void setKnowledge(KnowledgeProperties knowledge) {
        this.knowledge = knowledge != null ? knowledge : new KnowledgeProperties();
    }

    public AgentProperties getAgent() {
        return agent;
    }

    public void setAgent(AgentProperties agent) {
        this.agent = agent != null ? agent : new AgentProperties();
    }

    public HistoryProperties getHistory() {
        return history;
    }

    public void setHistory(HistoryProperties history) {
        this.history = history != null ? history : new HistoryProperties();
    }

    public ExecutionProperties getExecution() {
        return execution;
    }

    public void setExecution(ExecutionProperties execution) {
        this.execution = execution != null
                ? execution
                : new ExecutionProperties();
    }

    public ContextProperties getContext() {
        return context;
    }

    public void setContext(ContextProperties context) {
        this.context = context != null ? context : new ContextProperties();
    }

    public IdempotencyProperties getIdempotency() {
        return idempotency;
    }

    public void setIdempotency(IdempotencyProperties idempotency) {
        this.idempotency = idempotency != null
                ? idempotency
                : new IdempotencyProperties();
    }

    /**
     * Validates cross-field chat limits after Spring Boot has bound all values.
     */
    public void validate() {
        requirePositive("rag.chat.execution.max-candidate-attempts",
                execution.getMaxCandidateAttempts());
        requirePositive("rag.chat.execution.max-model-calls",
                execution.getMaxModelCalls());

        AgentProperties a = agent;
        requirePositive("rag.chat.agent.max-tool-rounds", a.getMaxToolRounds());
        requirePositive("rag.chat.agent.max-retrieval-calls",
                a.getMaxRetrievalCalls());
        requirePositive("rag.chat.agent.max-results-per-call",
                a.getMaxResultsPerCall());
        requirePositive("rag.chat.agent.max-unique-sources",
                a.getMaxUniqueSources());
        requirePositive("rag.chat.agent.max-tool-result-characters",
                a.getMaxToolResultCharacters());
        requirePositive("rag.chat.agent.max-tool-calls", a.getMaxToolCalls());
        requirePositive("rag.chat.agent.max-tool-calls-per-name",
                a.getMaxToolCallsPerName());
        requirePositive("rag.chat.agent.max-tool-result-characters-total",
                a.getMaxToolResultCharactersTotal());
        requirePositive("rag.chat.agent.tool-executor-threads",
                a.getToolExecutorThreads());
        requirePositive("rag.chat.agent.tool-executor-queue-capacity",
                a.getToolExecutorQueueCapacity());
        if (a.getMaxToolCallsPerName() > a.getMaxToolCalls()) {
            throw invalid("rag.chat.agent.max-tool-calls-per-name must not exceed "
                    + "rag.chat.agent.max-tool-calls");
        }
        if (a.getMaxToolResultCharacters()
                > a.getMaxToolResultCharactersTotal()) {
            throw invalid("rag.chat.agent.max-tool-result-characters must not exceed "
                    + "rag.chat.agent.max-tool-result-characters-total");
        }

        ContextProperties c = context;
        requirePositive("rag.chat.context.fallback-context-window",
                c.getFallbackContextWindow());
        requirePositive("rag.chat.context.output-reserve-tokens",
                c.getOutputReserveTokens());
        requirePositive("rag.chat.context.safety-margin-tokens",
                c.getSafetyMarginTokens());
        requirePositive("rag.chat.context.max-history-tokens",
                c.getMaxHistoryTokens());
        requirePositive("rag.chat.context.minimum-recent-turns",
                c.getMinimumRecentTurns());
        requirePositive("rag.chat.context.max-summary-tokens",
                c.getMaxSummaryTokens());
        requirePositive("rag.chat.context.minimum-mode-evidence-tokens",
                c.getMinimumModeEvidenceTokens());
        requirePositive("rag.chat.context.max-rag-context-tokens",
                c.getMaxRagContextTokens());
        requirePositive("rag.chat.context.max-tool-schema-tokens",
                c.getMaxToolSchemaTokens());
        requirePositive("rag.chat.context.compaction-trigger-tokens",
                c.getCompactionTriggerTokens());
        requirePositive("rag.chat.context.compaction-max-source-tokens",
                c.getCompactionMaxSourceTokens());
        requirePositive("rag.chat.context.compaction-max-output-tokens",
                c.getCompactionMaxOutputTokens());
        requirePositive("rag.chat.context.compaction-max-turns-per-call",
                c.getCompactionMaxTurnsPerCall());
        requirePositive("rag.chat.context.compaction-timeout-ms",
                c.getCompactionTimeoutMs());
        if (c.getOutputReserveTokens() + c.getSafetyMarginTokens()
                >= c.getFallbackContextWindow()) {
            throw invalid("rag.chat.context.output-reserve-tokens + "
                    + "safety-margin-tokens must be less than "
                    + "fallback-context-window");
        }
        if (c.getMaxSummaryTokens() > c.getMaxHistoryTokens()) {
            throw invalid("rag.chat.context.max-summary-tokens must not exceed "
                    + "max-history-tokens");
        }
        if (c.getMinimumModeEvidenceTokens() > c.getMaxRagContextTokens()) {
            throw invalid("rag.chat.context.minimum-mode-evidence-tokens must not exceed "
                    + "max-rag-context-tokens");
        }
        if (c.getCompactionMaxOutputTokens() > c.getMaxSummaryTokens()) {
            throw invalid("rag.chat.context.compaction-max-output-tokens must not exceed "
                    + "max-summary-tokens");
        }
        if (c.getCompactionMaxOutputTokens()
                >= c.getCompactionMaxSourceTokens()) {
            throw invalid("rag.chat.context.compaction-max-output-tokens must be less than "
                    + "compaction-max-source-tokens");
        }

        IdempotencyProperties i = idempotency;
        requirePositive("rag.chat.idempotency.retention-hours",
                i.getRetentionHours());
        requirePositive("rag.chat.idempotency.response-snapshot-max-bytes",
                i.getResponseSnapshotMaxBytes());
        requirePositive("rag.chat.idempotency.execution-snapshot-max-bytes",
                i.getExecutionSnapshotMaxBytes());
        requirePositive("rag.chat.idempotency.max-attempts", i.getMaxAttempts());
        requirePositive("rag.chat.idempotency.lease-grace-ms",
                i.getLeaseGraceMs());
        requirePositive("rag.chat.idempotency.cleanup-batch-size",
                i.getCleanupBatchSize());
        requirePositive("rag.chat.idempotency.cleanup-interval-ms",
                i.getCleanupIntervalMs());
        requirePositive("rag.chat.idempotency.cleanup-initial-delay-ms",
                i.getCleanupInitialDelayMs());
        if (i.getRetentionHours() > 168
                || i.getResponseSnapshotMaxBytes() < 65_536
                || i.getResponseSnapshotMaxBytes() > 2_097_152
                || i.getExecutionSnapshotMaxBytes() < 16_384
                || i.getExecutionSnapshotMaxBytes() > 262_144
                || i.getMaxAttempts() > 8
                || i.getLeaseGraceMs() > 60_000
                || i.getCleanupBatchSize() > 5_000
                || i.getCleanupIntervalMs() < 10_000
                || i.getCleanupIntervalMs() > 86_400_000
                || i.getCleanupInitialDelayMs() > 86_400_000) {
            throw invalid("rag.chat.idempotency values are outside their supported range");
        }
    }

    private static void requirePositive(String key, int value) {
        if (value <= 0) {
            throw invalid(key + " must be greater than zero");
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Invalid chat configuration: " + message);
    }

    public static class KnowledgeProperties {
        private String queryTransformer = "none";
        private int queryTransformTimeoutSeconds = 30;
        private int queryExpanderVariants = 2;
        private boolean queryExpanderIncludeOriginal = true;
        private int maxRetrievalQueries = 3;
        private boolean allowEmptyContext = false;

        public String getQueryTransformer() {
            return queryTransformer;
        }

        public void setQueryTransformer(String queryTransformer) {
            this.queryTransformer = queryTransformer;
        }

        public int getQueryTransformTimeoutSeconds() {
            return queryTransformTimeoutSeconds;
        }

        public void setQueryTransformTimeoutSeconds(int queryTransformTimeoutSeconds) {
            this.queryTransformTimeoutSeconds = queryTransformTimeoutSeconds;
        }

        public int getQueryExpanderVariants() {
            return queryExpanderVariants;
        }

        public void setQueryExpanderVariants(int queryExpanderVariants) {
            this.queryExpanderVariants = Math.max(1, Math.min(5, queryExpanderVariants));
        }

        public boolean isQueryExpanderIncludeOriginal() {
            return queryExpanderIncludeOriginal;
        }

        public void setQueryExpanderIncludeOriginal(boolean queryExpanderIncludeOriginal) {
            this.queryExpanderIncludeOriginal = queryExpanderIncludeOriginal;
        }

        public int getMaxRetrievalQueries() {
            return maxRetrievalQueries;
        }

        public void setMaxRetrievalQueries(int maxRetrievalQueries) {
            this.maxRetrievalQueries = Math.max(1, Math.min(5, maxRetrievalQueries));
        }

        public int getEffectiveQueryExpanderVariants() {
            int reservedOriginal = queryExpanderIncludeOriginal ? 1 : 0;
            return Math.min(
                    queryExpanderVariants,
                    Math.max(0, maxRetrievalQueries - reservedOriginal));
        }

        public int getPlannedRetrievalQueries() {
            return getEffectiveQueryExpanderVariants()
                    + (queryExpanderIncludeOriginal ? 1 : 0);
        }

        public boolean isQueryExpansionBudgetLimited() {
            return getEffectiveQueryExpanderVariants() < queryExpanderVariants;
        }

        public boolean isAllowEmptyContext() {
            return allowEmptyContext;
        }

        public void setAllowEmptyContext(boolean allowEmptyContext) {
            this.allowEmptyContext = allowEmptyContext;
        }
    }

    public static class AgentProperties {
        private boolean enabled = true;
        private int maxToolRounds = 3;
        private int maxRetrievalCalls = 3;
        private int maxResultsPerCall = 10;
        private int maxUniqueSources = 20;
        private int maxToolResultCharacters = 24_000;
        private int maxToolCalls = 6;
        private int maxToolCallsPerName = 3;
        private int maxToolResultCharactersTotal = 48_000;
        private int toolExecutorThreads = 8;
        private int toolExecutorQueueCapacity = 32;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxToolRounds() {
            return maxToolRounds;
        }

        public void setMaxToolRounds(int maxToolRounds) {
            this.maxToolRounds = maxToolRounds;
        }

        public int getMaxRetrievalCalls() {
            return maxRetrievalCalls;
        }

        public void setMaxRetrievalCalls(int maxRetrievalCalls) {
            this.maxRetrievalCalls = maxRetrievalCalls;
        }

        public int getMaxResultsPerCall() {
            return maxResultsPerCall;
        }

        public void setMaxResultsPerCall(int maxResultsPerCall) {
            this.maxResultsPerCall = maxResultsPerCall;
        }

        public int getMaxUniqueSources() {
            return maxUniqueSources;
        }

        public void setMaxUniqueSources(int maxUniqueSources) {
            this.maxUniqueSources = maxUniqueSources;
        }

        public int getMaxToolResultCharacters() {
            return maxToolResultCharacters;
        }

        public void setMaxToolResultCharacters(int maxToolResultCharacters) {
            this.maxToolResultCharacters = maxToolResultCharacters;
        }

        public int getMaxToolCalls() {
            return maxToolCalls;
        }

        public void setMaxToolCalls(int maxToolCalls) {
            this.maxToolCalls = maxToolCalls;
        }

        public int getMaxToolCallsPerName() {
            return maxToolCallsPerName;
        }

        public void setMaxToolCallsPerName(int maxToolCallsPerName) {
            this.maxToolCallsPerName = maxToolCallsPerName;
        }

        public int getMaxToolResultCharactersTotal() {
            return maxToolResultCharactersTotal;
        }

        public void setMaxToolResultCharactersTotal(int maxToolResultCharactersTotal) {
            this.maxToolResultCharactersTotal = maxToolResultCharactersTotal;
        }

        public int getToolExecutorThreads() {
            return toolExecutorThreads;
        }

        public void setToolExecutorThreads(int toolExecutorThreads) {
            this.toolExecutorThreads = toolExecutorThreads;
        }

        public int getToolExecutorQueueCapacity() {
            return toolExecutorQueueCapacity;
        }

        public void setToolExecutorQueueCapacity(int toolExecutorQueueCapacity) {
            this.toolExecutorQueueCapacity = toolExecutorQueueCapacity;
        }
    }

    public static class HistoryProperties {
        private int leaseTtlSeconds = 30;
        private int leaseRenewIntervalSeconds = 10;

        public int getLeaseTtlSeconds() {
            return leaseTtlSeconds;
        }

        public void setLeaseTtlSeconds(int leaseTtlSeconds) {
            this.leaseTtlSeconds = Math.max(10, leaseTtlSeconds);
        }

        public int getLeaseRenewIntervalSeconds() {
            return leaseRenewIntervalSeconds;
        }

        public void setLeaseRenewIntervalSeconds(int leaseRenewIntervalSeconds) {
            this.leaseRenewIntervalSeconds = Math.max(1, leaseRenewIntervalSeconds);
        }
    }

    public static class ExecutionProperties {
        private int maxCandidateAttempts = 3;
        private int maxModelCalls = 8;

        public int getMaxCandidateAttempts() {
            return maxCandidateAttempts;
        }

        public void setMaxCandidateAttempts(int maxCandidateAttempts) {
            this.maxCandidateAttempts = maxCandidateAttempts;
        }

        public int getMaxModelCalls() {
            return maxModelCalls;
        }

        public void setMaxModelCalls(int maxModelCalls) {
            this.maxModelCalls = maxModelCalls;
        }
    }

    public static class ContextProperties {
        private boolean adaptivePlanningEnabled = true;
        private int fallbackContextWindow = 32_768;
        private int outputReserveTokens = 4_096;
        private int safetyMarginTokens = 1_024;
        private int maxHistoryTokens = 12_000;
        private int minimumRecentTurns = 2;
        private int maxSummaryTokens = 2_048;
        private int minimumModeEvidenceTokens = 4_096;
        private int maxRagContextTokens = 16_000;
        private int maxToolSchemaTokens = 4_096;
        private boolean compactionEnabled = false;
        private int compactionTriggerTokens = 12_000;
        private int compactionMaxSourceTokens = 16_000;
        private int compactionMaxOutputTokens = 1_536;
        private int compactionMaxTurnsPerCall = 50;
        private int compactionTimeoutMs = 30_000;
        private String compactionModel;

        public boolean isAdaptivePlanningEnabled() {
            return adaptivePlanningEnabled;
        }

        public void setAdaptivePlanningEnabled(boolean adaptivePlanningEnabled) {
            this.adaptivePlanningEnabled = adaptivePlanningEnabled;
        }

        public int getFallbackContextWindow() {
            return fallbackContextWindow;
        }

        public void setFallbackContextWindow(int fallbackContextWindow) {
            this.fallbackContextWindow = fallbackContextWindow;
        }

        public int getOutputReserveTokens() {
            return outputReserveTokens;
        }

        public void setOutputReserveTokens(int outputReserveTokens) {
            this.outputReserveTokens = outputReserveTokens;
        }

        public int getSafetyMarginTokens() {
            return safetyMarginTokens;
        }

        public void setSafetyMarginTokens(int safetyMarginTokens) {
            this.safetyMarginTokens = safetyMarginTokens;
        }

        public int getMaxHistoryTokens() {
            return maxHistoryTokens;
        }

        public void setMaxHistoryTokens(int maxHistoryTokens) {
            this.maxHistoryTokens = maxHistoryTokens;
        }

        public int getMinimumRecentTurns() {
            return minimumRecentTurns;
        }

        public void setMinimumRecentTurns(int minimumRecentTurns) {
            this.minimumRecentTurns = minimumRecentTurns;
        }

        public int getMaxSummaryTokens() {
            return maxSummaryTokens;
        }

        public void setMaxSummaryTokens(int maxSummaryTokens) {
            this.maxSummaryTokens = maxSummaryTokens;
        }

        public int getMinimumModeEvidenceTokens() {
            return minimumModeEvidenceTokens;
        }

        public void setMinimumModeEvidenceTokens(int minimumModeEvidenceTokens) {
            this.minimumModeEvidenceTokens = minimumModeEvidenceTokens;
        }

        public int getMaxRagContextTokens() {
            return maxRagContextTokens;
        }

        public void setMaxRagContextTokens(int maxRagContextTokens) {
            this.maxRagContextTokens = maxRagContextTokens;
        }

        public int getMaxToolSchemaTokens() {
            return maxToolSchemaTokens;
        }

        public void setMaxToolSchemaTokens(int maxToolSchemaTokens) {
            this.maxToolSchemaTokens = maxToolSchemaTokens;
        }

        public boolean isCompactionEnabled() {
            return compactionEnabled;
        }

        public void setCompactionEnabled(boolean compactionEnabled) {
            this.compactionEnabled = compactionEnabled;
        }

        public int getCompactionTriggerTokens() {
            return compactionTriggerTokens;
        }

        public void setCompactionTriggerTokens(int compactionTriggerTokens) {
            this.compactionTriggerTokens = compactionTriggerTokens;
        }

        public int getCompactionMaxSourceTokens() {
            return compactionMaxSourceTokens;
        }

        public void setCompactionMaxSourceTokens(int compactionMaxSourceTokens) {
            this.compactionMaxSourceTokens = compactionMaxSourceTokens;
        }

        public int getCompactionMaxOutputTokens() {
            return compactionMaxOutputTokens;
        }

        public void setCompactionMaxOutputTokens(int compactionMaxOutputTokens) {
            this.compactionMaxOutputTokens = compactionMaxOutputTokens;
        }

        public int getCompactionMaxTurnsPerCall() {
            return compactionMaxTurnsPerCall;
        }

        public void setCompactionMaxTurnsPerCall(int compactionMaxTurnsPerCall) {
            this.compactionMaxTurnsPerCall = compactionMaxTurnsPerCall;
        }

        public int getCompactionTimeoutMs() {
            return compactionTimeoutMs;
        }

        public void setCompactionTimeoutMs(int compactionTimeoutMs) {
            this.compactionTimeoutMs = compactionTimeoutMs;
        }

        public String getCompactionModel() {
            return compactionModel;
        }

        public void setCompactionModel(String compactionModel) {
            this.compactionModel = compactionModel;
        }
    }

    public static class IdempotencyProperties {
        private boolean enabled = true;
        private int retentionHours = 24;
        private int responseSnapshotMaxBytes = 524_288;
        private int executionSnapshotMaxBytes = 65_536;
        private int maxAttempts = 3;
        private int leaseGraceMs = 10_000;
        private int cleanupBatchSize = 500;
        private int cleanupIntervalMs = 600_000;
        private int cleanupInitialDelayMs = 60_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRetentionHours() { return retentionHours; }
        public void setRetentionHours(int value) { this.retentionHours = value; }
        public int getResponseSnapshotMaxBytes() { return responseSnapshotMaxBytes; }
        public void setResponseSnapshotMaxBytes(int value) {
            this.responseSnapshotMaxBytes = value;
        }
        public int getExecutionSnapshotMaxBytes() { return executionSnapshotMaxBytes; }
        public void setExecutionSnapshotMaxBytes(int value) {
            this.executionSnapshotMaxBytes = value;
        }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int value) { this.maxAttempts = value; }
        public int getLeaseGraceMs() { return leaseGraceMs; }
        public void setLeaseGraceMs(int value) { this.leaseGraceMs = value; }
        public int getCleanupBatchSize() { return cleanupBatchSize; }
        public void setCleanupBatchSize(int value) { this.cleanupBatchSize = value; }
        public int getCleanupIntervalMs() { return cleanupIntervalMs; }
        public void setCleanupIntervalMs(int value) { this.cleanupIntervalMs = value; }
        public int getCleanupInitialDelayMs() { return cleanupInitialDelayMs; }
        public void setCleanupInitialDelayMs(int value) {
            this.cleanupInitialDelayMs = value;
        }
    }
}
