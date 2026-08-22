package com.springairag.core.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.retrieval.RetrievalFilterValidator;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps public request DTOs to server-owned chat execution commands.
 */
@Component
public class ChatCommandMapper {

    private final RagProperties ragProperties;
    private final DomainExtensionRegistry domainExtensions;
    private final RetrievalFilterValidator filterValidator;
    private final ObjectMapper objectMapper;

    public ChatCommandMapper(RagProperties ragProperties,
                             DomainExtensionRegistry domainExtensions) {
        this(ragProperties, domainExtensions, new ObjectMapper());
    }

    @Autowired
    public ChatCommandMapper(
            RagProperties ragProperties,
            DomainExtensionRegistry domainExtensions,
            ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.domainExtensions = domainExtensions;
        this.filterValidator = new RetrievalFilterValidator();
        this.objectMapper = objectMapper;
    }

    public ChatCommand map(
            ChatRequest request,
            RetrievalScope scope,
            ChatPrincipal principal) {
        if (request == null) {
            throw new IllegalArgumentException("chat request must not be null");
        }
        ChatMode mode = request.getMode();
        if (mode == ChatMode.PLAIN && hasRetrievalOverride(request)) {
            throw new RagException(
                    ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                    "Retrieval options are not allowed in PLAIN mode");
        }
        if (request.getDomainId() != null
                && !request.getDomainId().isBlank()
                && !domainExtensions.hasDomain(request.getDomainId())) {
            throw new RagException(
                    ErrorCode.UNKNOWN_DOMAIN,
                    "Unknown domain '" + request.getDomainId() + "'");
        }

        RetrievalConfig domainConfig = request.getDomainId() == null
                ? null
                : domainExtensions.getExtension(request.getDomainId()).getRetrievalConfig();
        RetrievalOptions options = RetrievalOptions.from(
                ragProperties.getRetrieval(),
                domainConfig,
                request.isMaxResultsExplicitlySet(),
                request.getMaxResults(),
                request.isUseHybridSearchExplicitlySet(),
                request.isUseHybridSearch(),
                request.isUseRerankExplicitlySet(),
                request.isUseRerank());
        ChatPrincipal effectivePrincipal = principal != null ? principal : ChatPrincipal.local();
        String sessionId = SessionIdValidator.resolve(request.getSessionId());
        RetrievalFilters filters = filterValidator.validate(request.getFilters());
        return new ChatCommand(
                request.getMessage(),
                sessionId,
                effectivePrincipal,
                effectivePrincipal.memoryConversationId(sessionId),
                mode,
                MemoryMode.SERVER,
                request.getModel(),
                request.getDomainId(),
                scope,
                options,
                request.getMetadata() != null ? request.getMetadata() : Map.of())
                .withFilters(filters);
    }

    /**
     * Rebuilds a native command from the immutable operation snapshot. This
     * path deliberately does not consult the current domain registry or ACL
     * resolver; the operation service has already verified replay authority.
     */
    public ChatCommand mapFromExecutionSnapshot(
            ChatRequest request,
            ChatPrincipal principal,
            String sessionId,
            String executionSnapshot) {
        try {
            JsonNode snapshot = objectMapper.readTree(executionSnapshot);
            if (snapshot == null
                    || snapshot.path("executionSnapshotVersion").asInt() != 1) {
                throw invalidSnapshot();
            }
            ChatMode mode = ChatMode.valueOf(snapshot.path("mode").asText());
            MemoryMode memory = MemoryMode.valueOf(
                    snapshot.path("memoryMode").asText());
            List<String> candidates = textList(
                    snapshot.path("resolvedCandidates"));
            String declaredModel = snapshot.path(
                    "declaredModelIdentifier").asText(null);
            String modelRef = !candidates.isEmpty()
                    ? candidates.getFirst()
                    : "DEFAULT".equals(declaredModel)
                            ? null : declaredModel;
            RetrievalOptions options = retrievalOptions(
                    snapshot.path("retrievalOptions"));
            RetrievalScope scope = retrievalScope(
                    snapshot.path("effectiveScope"));
            RetrievalFilters filters = filterValidator.validate(
                    request.getFilters());
            if (mode == ChatMode.PLAIN && !filters.isEmpty()) {
                throw new RagException(
                        ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                        "Retrieval options are not allowed in PLAIN mode");
            }
            ChatPrincipal effectivePrincipal = principal != null
                    ? principal : ChatPrincipal.local();
            return new ChatCommand(
                    request.getMessage(),
                    sessionId,
                    effectivePrincipal,
                    effectivePrincipal.memoryConversationId(sessionId),
                    mode,
                    memory,
                    modelRef,
                    blankAsNull(snapshot.path("domainId").asText(null)),
                    scope,
                    options,
                    request.getMetadata() != null
                            ? request.getMetadata() : Map.of(),
                    List.of(new ChatInputMessage(
                            ChatInputMessage.Role.USER,
                            request.getMessage())),
                    candidates)
                    .withFilters(filters);
        } catch (RagException e) {
            throw e;
        } catch (Exception e) {
            throw invalidSnapshot();
        }
    }

    private RetrievalOptions retrievalOptions(JsonNode node) {
        if (node == null || !node.isObject()
                || !node.has("maxResults")
                || !node.has("minScore")
                || !node.has("useHybridSearch")
                || !node.has("useRerank")
                || !node.has("vectorWeight")
                || !node.has("fulltextWeight")) {
            throw invalidSnapshot();
        }
        return new RetrievalOptions(
                node.path("maxResults").asInt(),
                node.path("minScore").asDouble(),
                node.path("useHybridSearch").asBoolean(),
                node.path("useRerank").asBoolean(),
                node.path("vectorWeight").asDouble(),
                node.path("fulltextWeight").asDouble());
    }

    private RetrievalScope retrievalScope(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalidSnapshot();
        }
        try {
            return new RetrievalScope(
                    RetrievalScope.CollectionFilter.valueOf(
                            node.path("collectionFilter").asText()),
                    longList(node.path("collectionIds")),
                    longList(node.path("documentIds")),
                    blankAsNull(node.path("documentType").asText(null)),
                    node.path("matchNone").asBoolean());
        } catch (IllegalArgumentException e) {
            throw invalidSnapshot();
        }
    }

    private List<Long> longList(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw invalidSnapshot();
        }
        List<Long> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isIntegralNumber() || item.longValue() <= 0) {
                throw invalidSnapshot();
            }
            values.add(item.longValue());
        }
        return values;
    }

    private List<String> textList(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw invalidSnapshot();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw invalidSnapshot();
            }
            values.add(item.asText());
        }
        return List.copyOf(values);
    }

    private String blankAsNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private RagException invalidSnapshot() {
        return new RagException(
                ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                "Chat execution snapshot is invalid");
    }

    private boolean hasRetrievalOverride(ChatRequest request) {
        return request.isMaxResultsExplicitlySet()
                || request.isUseHybridSearchExplicitlySet()
                || request.isUseRerankExplicitlySet()
                || request.getCollectionScopeMode() != null
                || request.getCollectionIds() != null
                || request.getCollectionKeys() != null
                || request.getDocumentIds() != null
                || request.getFilters() != null;
    }
}
