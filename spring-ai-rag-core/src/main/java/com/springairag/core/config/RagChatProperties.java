package com.springairag.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
    private StaticKnowledgeProperties staticKnowledge =
            new StaticKnowledgeProperties();
    private SkillProperties skills = new SkillProperties();
    private HttpToolProperties httpTools = new HttpToolProperties();

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

    public StaticKnowledgeProperties getStaticKnowledge() {
        return staticKnowledge;
    }

    public void setStaticKnowledge(StaticKnowledgeProperties staticKnowledge) {
        this.staticKnowledge = staticKnowledge != null
                ? staticKnowledge
                : new StaticKnowledgeProperties();
    }

    public SkillProperties getSkills() {
        return skills;
    }

    public void setSkills(SkillProperties skills) {
        this.skills = skills != null ? skills : new SkillProperties();
    }

    public HttpToolProperties getHttpTools() {
        return httpTools;
    }

    public void setHttpTools(HttpToolProperties httpTools) {
        this.httpTools = httpTools != null
                ? httpTools
                : new HttpToolProperties();
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
        staticKnowledge.validate();
        skills.validate();
        httpTools.validate();
    }

    private static void requirePositive(String key, int value) {
        if (value <= 0) {
            throw invalid(key + " must be greater than zero");
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Invalid chat configuration: " + message);
    }

    public static class StaticKnowledgeProperties {
        private boolean enabled;
        private List<String> locations = new ArrayList<>();
        private List<String> fileExtensions =
                new ArrayList<>(List.of("md", "markdown", "txt"));
        private int maxFilesPerRoot = 200;
        private int maxFileBytes = 262_144;
        private int maxTotalBytes = 10_485_760;
        private int chunkMaxCharacters = 4_000;
        private int chunkOverlapCharacters = 200;
        private int retrievalMaxResults = 5;
        private int retrievalMaxResultCharacters = 24_000;
        private boolean failFast = true;
        private String visibility = "GLOBAL";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getLocations() { return locations; }
        public void setLocations(List<String> locations) {
            this.locations = locations == null
                    ? new ArrayList<>()
                    : new ArrayList<>(locations);
        }
        public List<String> getFileExtensions() { return fileExtensions; }
        public void setFileExtensions(List<String> fileExtensions) {
            this.fileExtensions = fileExtensions == null
                    ? new ArrayList<>()
                    : new ArrayList<>(fileExtensions);
        }
        public int getMaxFilesPerRoot() { return maxFilesPerRoot; }
        public void setMaxFilesPerRoot(int value) { this.maxFilesPerRoot = value; }
        public int getMaxFileBytes() { return maxFileBytes; }
        public void setMaxFileBytes(int value) { this.maxFileBytes = value; }
        public int getMaxTotalBytes() { return maxTotalBytes; }
        public void setMaxTotalBytes(int value) { this.maxTotalBytes = value; }
        public int getChunkMaxCharacters() { return chunkMaxCharacters; }
        public void setChunkMaxCharacters(int value) {
            this.chunkMaxCharacters = value;
        }
        public int getChunkOverlapCharacters() { return chunkOverlapCharacters; }
        public void setChunkOverlapCharacters(int value) {
            this.chunkOverlapCharacters = value;
        }
        public int getRetrievalMaxResults() { return retrievalMaxResults; }
        public void setRetrievalMaxResults(int value) {
            this.retrievalMaxResults = value;
        }
        public int getRetrievalMaxResultCharacters() {
            return retrievalMaxResultCharacters;
        }
        public void setRetrievalMaxResultCharacters(int value) {
            this.retrievalMaxResultCharacters = value;
        }
        public boolean isFailFast() { return failFast; }
        public void setFailFast(boolean failFast) { this.failFast = failFast; }
        public String getVisibility() { return visibility; }
        public void setVisibility(String visibility) { this.visibility = visibility; }

        private void validate() {
            requirePositive("rag.chat.static-knowledge.max-files-per-root",
                    maxFilesPerRoot);
            requirePositive("rag.chat.static-knowledge.max-file-bytes",
                    maxFileBytes);
            requirePositive("rag.chat.static-knowledge.max-total-bytes",
                    maxTotalBytes);
            requirePositive("rag.chat.static-knowledge.chunk-max-characters",
                    chunkMaxCharacters);
            requirePositive("rag.chat.static-knowledge.retrieval-max-results",
                    retrievalMaxResults);
            requirePositive("rag.chat.static-knowledge.retrieval-max-result-characters",
                    retrievalMaxResultCharacters);
            if (chunkOverlapCharacters < 0
                    || chunkOverlapCharacters >= chunkMaxCharacters) {
                throw invalid("rag.chat.static-knowledge.chunk-overlap-characters "
                        + "must be >= 0 and less than chunk-max-characters");
            }
            if (!"GLOBAL".equalsIgnoreCase(visibility)) {
                throw invalid("rag.chat.static-knowledge.visibility must be GLOBAL");
            }
            if (fileExtensions == null || fileExtensions.isEmpty()
                    || fileExtensions.stream().anyMatch(value ->
                    value == null || value.isBlank()
                            || value.contains("/") || value.contains("\\"))) {
                throw invalid("rag.chat.static-knowledge.file-extensions are invalid");
            }
        }
    }

    public static class SkillProperties {
        private boolean enabled;
        private List<String> locations = new ArrayList<>();
        private int maxSkills = 50;
        private int maxSkillBodyBytes = 131_072;
        private int maxReferenceBytes = 262_144;
        private int maxCatalogCharacters = 24_000;
        private int maxLoadsPerRequest = 4;
        private int maxReferenceReadsPerRequest = 8;
        private boolean failFast = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getLocations() { return locations; }
        public void setLocations(List<String> locations) {
            this.locations = locations == null
                    ? new ArrayList<>()
                    : new ArrayList<>(locations);
        }
        public int getMaxSkills() { return maxSkills; }
        public void setMaxSkills(int value) { this.maxSkills = value; }
        public int getMaxSkillBodyBytes() { return maxSkillBodyBytes; }
        public void setMaxSkillBodyBytes(int value) {
            this.maxSkillBodyBytes = value;
        }
        public int getMaxReferenceBytes() { return maxReferenceBytes; }
        public void setMaxReferenceBytes(int value) {
            this.maxReferenceBytes = value;
        }
        public int getMaxCatalogCharacters() { return maxCatalogCharacters; }
        public void setMaxCatalogCharacters(int value) {
            this.maxCatalogCharacters = value;
        }
        public int getMaxLoadsPerRequest() { return maxLoadsPerRequest; }
        public void setMaxLoadsPerRequest(int value) {
            this.maxLoadsPerRequest = value;
        }
        public int getMaxReferenceReadsPerRequest() {
            return maxReferenceReadsPerRequest;
        }
        public void setMaxReferenceReadsPerRequest(int value) {
            this.maxReferenceReadsPerRequest = value;
        }
        public boolean isFailFast() { return failFast; }
        public void setFailFast(boolean failFast) { this.failFast = failFast; }

        private void validate() {
            requirePositive("rag.chat.skills.max-skills", maxSkills);
            requirePositive("rag.chat.skills.max-skill-body-bytes",
                    maxSkillBodyBytes);
            requirePositive("rag.chat.skills.max-reference-bytes",
                    maxReferenceBytes);
            requirePositive("rag.chat.skills.max-catalog-characters",
                    maxCatalogCharacters);
            requirePositive("rag.chat.skills.max-loads-per-request",
                    maxLoadsPerRequest);
            requirePositive("rag.chat.skills.max-reference-reads-per-request",
                    maxReferenceReadsPerRequest);
        }
    }

    public static class HttpToolProperties {
        private boolean enabled;
        private List<HttpEndpointProperties> endpoints = new ArrayList<>();
        private int maxTotalResponseBytes = 262_144;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<HttpEndpointProperties> getEndpoints() {
            return endpoints;
        }
        public void setEndpoints(List<HttpEndpointProperties> endpoints) {
            this.endpoints = endpoints == null
                    ? new ArrayList<>()
                    : new ArrayList<>(endpoints);
        }
        public int getMaxTotalResponseBytes() {
            return maxTotalResponseBytes;
        }
        public void setMaxTotalResponseBytes(int maxTotalResponseBytes) {
            this.maxTotalResponseBytes = maxTotalResponseBytes;
        }

        private void validate() {
            requirePositive(
                    "rag.chat.http-tools.max-total-response-bytes",
                    maxTotalResponseBytes);
            if (maxTotalResponseBytes > 4_194_304) {
                throw invalid(
                        "rag.chat.http-tools.max-total-response-bytes must not exceed 4194304");
            }
            if (endpoints == null) {
                throw invalid("rag.chat.http-tools.endpoints must not be null");
            }
            for (HttpEndpointProperties endpoint : endpoints) {
                if (endpoint == null) {
                    throw invalid("rag.chat.http-tools.endpoints must not contain null");
                }
                endpoint.validate(maxTotalResponseBytes);
            }
        }
    }

    public static class HttpEndpointProperties {
        private String toolName;
        private String capability;
        private String skillName;
        private String baseUrl;
        private String path = "/";
        private String method = "GET";
        private List<HttpQueryParameterProperties> queryParameters =
                new ArrayList<>();
        private List<String> responseContentTypes =
                new ArrayList<>(List.of("application/json"));
        private int maxCallsPerRequest = 2;
        private int timeoutMs = 5_000;
        private int maxResponseBytes = 65_536;
        private int maxResultCharacters = 24_000;
        private int maxJsonDepth = 12;
        private int maxJsonNodes = 1_000;
        private int maxJsonArrayItems = 100;
        private String credentialEnv;
        private String credentialHeader = "Authorization";

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public String getCapability() { return capability; }
        public void setCapability(String capability) { this.capability = capability; }
        public String getSkillName() { return skillName; }
        public void setSkillName(String skillName) { this.skillName = skillName; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public List<HttpQueryParameterProperties> getQueryParameters() {
            return queryParameters;
        }
        public void setQueryParameters(
                List<HttpQueryParameterProperties> queryParameters) {
            this.queryParameters = queryParameters == null
                    ? new ArrayList<>()
                    : new ArrayList<>(queryParameters);
        }
        public List<String> getResponseContentTypes() {
            return responseContentTypes;
        }
        public void setResponseContentTypes(List<String> responseContentTypes) {
            this.responseContentTypes = responseContentTypes == null
                    ? new ArrayList<>()
                    : new ArrayList<>(responseContentTypes);
        }
        public int getMaxCallsPerRequest() { return maxCallsPerRequest; }
        public void setMaxCallsPerRequest(int maxCallsPerRequest) {
            this.maxCallsPerRequest = maxCallsPerRequest;
        }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }
        public int getMaxResultCharacters() { return maxResultCharacters; }
        public void setMaxResultCharacters(int maxResultCharacters) {
            this.maxResultCharacters = maxResultCharacters;
        }
        public int getMaxJsonDepth() { return maxJsonDepth; }
        public void setMaxJsonDepth(int maxJsonDepth) { this.maxJsonDepth = maxJsonDepth; }
        public int getMaxJsonNodes() { return maxJsonNodes; }
        public void setMaxJsonNodes(int maxJsonNodes) { this.maxJsonNodes = maxJsonNodes; }
        public int getMaxJsonArrayItems() { return maxJsonArrayItems; }
        public void setMaxJsonArrayItems(int maxJsonArrayItems) {
            this.maxJsonArrayItems = maxJsonArrayItems;
        }
        public String getCredentialEnv() { return credentialEnv; }
        public void setCredentialEnv(String credentialEnv) {
            this.credentialEnv = credentialEnv;
        }
        public String getCredentialHeader() { return credentialHeader; }
        public void setCredentialHeader(String credentialHeader) {
            this.credentialHeader = credentialHeader;
        }

        private void validate(int totalResponseBytes) {
            if (blank(toolName) || !toolName.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
                throw invalid("rag.chat.http-tools endpoint tool-name is invalid");
            }
            if (blank(capability) || !capability.matches(
                    "[a-z0-9]+(?:[._:-][a-z0-9]+)*")) {
                throw invalid("rag.chat.http-tools endpoint capability is invalid");
            }
            if (blank(skillName)
                    || !skillName.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
                throw invalid("rag.chat.http-tools endpoint skill-name is invalid");
            }
            if (blank(baseUrl)) {
                throw invalid("rag.chat.http-tools endpoint base-url is required");
            }
            try {
                java.net.URI uri = java.net.URI.create(baseUrl.trim());
                if (!"https".equalsIgnoreCase(uri.getScheme())
                        || blank(uri.getHost())
                        || (uri.getPath() != null
                        && !uri.getPath().isBlank()
                        && !"/".equals(uri.getPath()))
                        || uri.getUserInfo() != null
                        || uri.getQuery() != null
                        || uri.getFragment() != null) {
                    throw invalid(
                            "rag.chat.http-tools endpoint base-url must be an https origin");
                }
            } catch (IllegalArgumentException e) {
                throw invalid("rag.chat.http-tools endpoint base-url is invalid");
            }
            if (blank(path) || !path.startsWith("/")
                    || path.contains("\\") || path.contains("..")
                    || path.contains("%")
                    || path.contains("#") || path.contains("?")
                    || path.indexOf('\0') >= 0
                    || path.chars().anyMatch(Character::isISOControl)) {
                throw invalid("rag.chat.http-tools endpoint path is unsafe");
            }
            if (!"GET".equalsIgnoreCase(method)
                    && !"HEAD".equalsIgnoreCase(method)) {
                throw invalid(
                        "rag.chat.http-tools endpoint method must be GET or HEAD");
            }
            requirePositive("rag.chat.http-tools endpoint max-calls-per-request",
                    maxCallsPerRequest);
            requirePositive("rag.chat.http-tools endpoint timeout-ms", timeoutMs);
            requirePositive("rag.chat.http-tools endpoint max-response-bytes",
                    maxResponseBytes);
            requirePositive("rag.chat.http-tools endpoint max-result-characters",
                    maxResultCharacters);
            requirePositive("rag.chat.http-tools endpoint max-json-depth",
                    maxJsonDepth);
            requirePositive("rag.chat.http-tools endpoint max-json-nodes",
                    maxJsonNodes);
            requirePositive("rag.chat.http-tools endpoint max-json-array-items",
                    maxJsonArrayItems);
            if (timeoutMs > 30_000
                    || maxResponseBytes > 4_194_304
                    || maxResponseBytes > totalResponseBytes
                    || maxResultCharacters < 1_024
                    || maxResultCharacters > 2_000_000) {
                throw invalid(
                        "rag.chat.http-tools endpoint limits are outside their supported range");
            }
            if (queryParameters == null) {
                throw invalid(
                        "rag.chat.http-tools endpoint query-parameters must not be null");
            }
            Set<String> names = new java.util.HashSet<>();
            for (HttpQueryParameterProperties parameter : queryParameters) {
                if (parameter == null || blank(parameter.getName())
                        || !parameter.getName().matches(
                        "[A-Za-z][A-Za-z0-9_-]{0,31}")
                        || !names.add(parameter.getName())) {
                    throw invalid(
                            "rag.chat.http-tools endpoint query parameter is invalid");
                }
                parameter.validate();
            }
            if (responseContentTypes == null || responseContentTypes.isEmpty()
                    || responseContentTypes.stream().anyMatch(value ->
                    blank(value) || value.contains(";")
                            || value.contains("*"))) {
                throw invalid(
                        "rag.chat.http-tools endpoint response-content-types are invalid");
            }
            if (credentialEnv != null && !credentialEnv.isBlank()
                    && !credentialEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw invalid(
                        "rag.chat.http-tools endpoint credential-env is invalid");
            }
            if (credentialHeader == null || credentialHeader.isBlank()
                    || !credentialHeader.matches(
                    "[A-Za-z0-9!#$%&'*+.^_`|~-]+")) {
                throw invalid(
                        "rag.chat.http-tools endpoint credential-header is invalid");
            }
        }
    }

    public static class HttpQueryParameterProperties {
        private String name;
        private boolean required = true;
        private int maxLength = 128;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public int getMaxLength() { return maxLength; }
        public void setMaxLength(int maxLength) { this.maxLength = maxLength; }

        private void validate() {
            requirePositive(
                    "rag.chat.http-tools endpoint query-parameter max-length",
                    maxLength);
            if (maxLength > 4_096) {
                throw invalid(
                        "rag.chat.http-tools endpoint query-parameter max-length "
                                + "must not exceed 4096");
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
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
