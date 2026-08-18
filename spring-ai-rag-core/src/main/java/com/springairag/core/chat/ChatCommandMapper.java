package com.springairag.core.chat;

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
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps public request DTOs to server-owned chat execution commands.
 */
@Component
public class ChatCommandMapper {

    private final RagProperties ragProperties;
    private final DomainExtensionRegistry domainExtensions;
    private final RetrievalFilterValidator filterValidator;

    public ChatCommandMapper(RagProperties ragProperties,
                             DomainExtensionRegistry domainExtensions) {
        this.ragProperties = ragProperties;
        this.domainExtensions = domainExtensions;
        this.filterValidator = new RetrievalFilterValidator();
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
