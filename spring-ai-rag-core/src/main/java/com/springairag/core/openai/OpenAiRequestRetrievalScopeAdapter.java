package com.springairag.core.openai;

import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 合成 OpenAI body/header 的 Collection 范围并委托统一 ACL resolver。
 */
@Component
@ConditionalOnProperty(
        prefix = "rag.openai-compatibility",
        name = "enabled",
        havingValue = "true")
public class OpenAiRequestRetrievalScopeAdapter {

    public static final String COLLECTION_KEY_HEADER = "X-RAG-Collection-Key";

    private final CollectionRetrievalScopeResolver resolver;
    private final RagProperties properties;

    public OpenAiRequestRetrievalScopeAdapter(
            CollectionRetrievalScopeResolver resolver,
            RagProperties properties) {
        this.resolver = resolver;
        this.properties = properties;
    }

    public RetrievalScope resolve(
            OpenAiChatCompletionRequest.RagOptions rag,
            HttpServletRequest request) {
        OpenAiChatCompletionRequest.Scope body =
                rag != null ? rag.getScope() : null;
        List<String> headerKeys = headerKeys(request);
        boolean hasHeader = !headerKeys.isEmpty();
        boolean hasBody = body != null;

        if (!hasBody && !hasHeader
                && properties.getOpenAiCompatibility().isRequireExplicitScope()) {
            throw OpenAiProtocolException.invalid(
                    "This deployment requires an explicit rag.scope or "
                            + COLLECTION_KEY_HEADER,
                    "rag.scope",
                    "RAG_SCOPE_REQUIRED");
        }
        if (body != null && !body.getAdditionalProperties().isEmpty()) {
            throw OpenAiProtocolException.invalid(
                    "Unsupported rag.scope fields: "
                            + body.getAdditionalProperties().keySet(),
                    "rag.scope",
                    "unsupported_parameter");
        }

        CollectionScopeMode mode = body != null ? body.getMode() : null;
        List<Long> ids = body != null ? body.getCollectionIds() : null;
        List<String> bodyKeys = body != null ? body.getCollectionKeys() : null;
        List<String> keys = bodyKeys;
        if (hasHeader) {
            if (mode != null && mode != CollectionScopeMode.SELECTED_COLLECTIONS) {
                throw OpenAiProtocolException.invalid(
                        "Collection header conflicts with rag.scope.mode",
                        "rag.scope",
                        "scope_conflict");
            }
            if (bodyKeys != null
                    && !new LinkedHashSet<>(bodyKeys)
                    .equals(new LinkedHashSet<>(headerKeys))) {
                throw OpenAiProtocolException.invalid(
                        "Collection header conflicts with rag.scope.collection_keys",
                        "rag.scope",
                        "scope_conflict");
            }
            mode = CollectionScopeMode.SELECTED_COLLECTIONS;
            keys = headerKeys;
        }

        RagApiKey caller = ApiKeyCollectionAccess.currentKey(request);
        try {
            return resolver.resolve(
                    mode,
                    ids,
                    keys,
                    rag != null ? rag.getDocumentIds() : null,
                    null,
                    caller);
        } catch (SecurityException e) {
            throw new OpenAiProtocolException(
                    403,
                    "The current API key is not allowed to access the requested Collection",
                    "permission_error",
                    "rag.scope",
                    "collection_not_allowed");
        } catch (IllegalArgumentException e) {
            throw OpenAiProtocolException.invalid(
                    e.getMessage(), "rag.scope", "invalid_scope");
        }
    }

    private List<String> headerKeys(HttpServletRequest request) {
        if (request == null || request.getHeaders(COLLECTION_KEY_HEADER) == null) {
            return List.of();
        }
        List<String> raw = Collections.list(
                request.getHeaders(COLLECTION_KEY_HEADER));
        if (raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank() || value.contains(",")) {
                throw OpenAiProtocolException.invalid(
                        COLLECTION_KEY_HEADER
                                + " must be repeated once per non-blank Collection key",
                        COLLECTION_KEY_HEADER,
                        "invalid_header");
            }
            normalized.add(value.trim());
        }
        return List.copyOf(normalized);
    }
}
