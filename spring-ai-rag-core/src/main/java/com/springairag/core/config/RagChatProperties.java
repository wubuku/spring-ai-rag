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

    public static class KnowledgeProperties {
        private String queryTransformer = "none";
        private int queryTransformTimeoutSeconds = 10;
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
}
