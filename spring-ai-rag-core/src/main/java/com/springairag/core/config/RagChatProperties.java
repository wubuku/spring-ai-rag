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

    public static class KnowledgeProperties {
        private String queryTransformer = "none";
        private int queryTransformTimeoutSeconds = 30;
        private int queryExpanderVariants = 2;
        private boolean queryExpanderIncludeOriginal = true;
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
    }
}
