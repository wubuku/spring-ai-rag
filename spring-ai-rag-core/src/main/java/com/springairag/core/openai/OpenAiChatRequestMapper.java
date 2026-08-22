package com.springairag.core.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatInputMessage;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.MemoryMode;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.config.RagProperties;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.retrieval.RetrievalFilterValidator;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.api.enums.ChatMode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 将 OpenAI DTO 映射为 transport-neutral {@link ChatCommand}。
 */
@Component
@ConditionalOnProperty(
        prefix = "rag.openai-compatibility",
        name = "enabled",
        havingValue = "true")
public class OpenAiChatRequestMapper {

    private static final int MAX_MESSAGES = 100;
    private static final int MAX_CONTENT_CHARACTERS = 1_000_000;

    private final OpenAiModelAliasRegistry aliasRegistry;
    private final OpenAiRequestRetrievalScopeAdapter scopeAdapter;
    private final RagProperties properties;
    private final RetrievalFilterValidator filterValidator = new RetrievalFilterValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiChatRequestMapper(
            OpenAiModelAliasRegistry aliasRegistry,
            OpenAiRequestRetrievalScopeAdapter scopeAdapter,
            RagProperties properties) {
        this.aliasRegistry = aliasRegistry;
        this.scopeAdapter = scopeAdapter;
        this.properties = properties;
    }

    public MappedRequest map(
            OpenAiChatCompletionRequest request,
            HttpServletRequest httpRequest) {
        return map(request, httpRequest, null);
    }

    public MappedRequest map(
            OpenAiChatCompletionRequest request,
            HttpServletRequest httpRequest,
            String sessionIdOverride) {
        Declaration declaration = validateDeclaration(
                request, headerValues(httpRequest));
        OpenAiChatCompletionRequest.RagOptions rag = request.getRag();
        OpenAiModelAliasRegistry.ResolvedAlias alias = aliasRegistry.resolve(
                request.getModel(),
                rag != null ? rag.getMode() : null,
                rag != null ? rag.getMemory() : null);
        if (alias.mode() == ChatMode.PLAIN && !declaration.filters().isEmpty()) {
            throw OpenAiProtocolException.invalid(
                    "rag.filters is not allowed when mode is PLAIN",
                    "rag.filters",
                    "unsupported_parameter");
        }
        RetrievalScope scope = scopeAdapter.resolve(rag, httpRequest);

        ChatPrincipal principal = ChatPrincipal.from(httpRequest);
        String sessionId = sessionIdOverride != null
                ? sessionIdOverride
                : "oai-" + UUID.randomUUID().toString().replace("-", "");
        RetrievalOptions retrievalOptions = RetrievalOptions.from(
                properties.getRetrieval(),
                null,
                false,
                0,
                false,
                false,
                false,
                false);
        ChatCommand command = new ChatCommand(
                declaration.latestUser(),
                sessionId,
                principal,
                principal.memoryConversationId(sessionId),
                alias.mode(),
                alias.memory(),
                alias.candidates().isEmpty()
                        ? null
                        : alias.candidates().getFirst(),
                null,
                scope,
                retrievalOptions,
                Map.of(),
                declaration.inputMessages(),
                alias.candidates())
                .withFilters(declaration.filters());
        return new MappedRequest(
                alias.alias(),
                Boolean.TRUE.equals(request.getStream()),
                command);
    }

    /**
     * Validates only request syntax and fields whose meaning does not depend on
     * the current alias registry or Collection ACL. This is safe to run before
     * durable operation lookup.
     */
    public Declaration validateDeclaration(
            OpenAiChatCompletionRequest request) {
        return validateDeclaration(request, List.of());
    }

    public Declaration validateDeclaration(
            OpenAiChatCompletionRequest request,
            List<String> collectionHeaderValues) {
        validateRequest(request);
        OpenAiChatCompletionRequest.RagOptions rag = request.getRag();
        validateRagShape(rag);
        RetrievalFilters filters = resolveFilters(rag);
        ChatMode declaredMode = rag != null && rag.getMode() != null
                ? rag.getMode()
                : ChatMode.KNOWLEDGE;
        if (declaredMode == ChatMode.PLAIN) {
            if (rag != null && rag.getScope() != null) {
                throw OpenAiProtocolException.invalid(
                        "rag.scope is not allowed when mode is PLAIN",
                        "rag.scope",
                        "unsupported_parameter");
            }
            if (rag != null && rag.getDocumentIds() != null) {
                throw OpenAiProtocolException.invalid(
                        "rag.document_ids is not allowed when mode is PLAIN",
                        "rag.document_ids",
                        "unsupported_parameter");
            }
            if (collectionHeaderValues != null
                    && !collectionHeaderValues.isEmpty()) {
                throw OpenAiProtocolException.invalid(
                        "X-RAG-Collection-Key is not allowed when mode is PLAIN",
                        OpenAiRequestRetrievalScopeAdapter.COLLECTION_KEY_HEADER,
                        "unsupported_parameter");
            }
        }
        if (rag != null && rag.getMode() == ChatMode.PLAIN
                && !filters.isEmpty()) {
            throw OpenAiProtocolException.invalid(
                    "rag.filters is not allowed when mode is PLAIN",
                    "rag.filters",
                    "unsupported_parameter");
        }
        if (rag != null && rag.getMemory() != null
                && !rag.getMemory().isBlank()) {
            try {
                com.springairag.core.chat.MemoryMode.valueOf(
                        rag.getMemory().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw OpenAiProtocolException.invalid(
                        "rag.memory must be STATELESS or SERVER",
                        "rag.memory",
                        "invalid_value");
            }
        }
        List<ChatInputMessage> inputMessages = parseMessages(
                request.getMessages());
        String latestUser = inputMessages.stream()
                .filter(message -> message.role() == ChatInputMessage.Role.USER)
                .reduce((left, right) -> right)
                .orElseThrow()
                .content();
        return new Declaration(latestUser, inputMessages, filters);
    }

    private List<String> headerValues(HttpServletRequest request) {
        if (request == null
                || request.getHeaders(
                        OpenAiRequestRetrievalScopeAdapter.COLLECTION_KEY_HEADER)
                        == null) {
            return List.of();
        }
        return Collections.list(request.getHeaders(
                OpenAiRequestRetrievalScopeAdapter.COLLECTION_KEY_HEADER));
    }

    /**
     * Rebuilds an execution command from the first claim's immutable snapshot.
     * No alias registry, domain registry or current Collection resolver is
     * consulted here.
     */
    public MappedRequest mapFromExecutionSnapshot(
            OpenAiChatCompletionRequest request,
            HttpServletRequest httpRequest,
            String sessionId,
            String executionSnapshot) {
        Declaration declaration = validateDeclaration(
                request, headerValues(httpRequest));
        try {
            JsonNode snapshot = objectMapper.readTree(executionSnapshot);
            if (snapshot == null
                    || snapshot.path("executionSnapshotVersion").asInt() != 1) {
                throw invalidSnapshot();
            }
            ChatMode mode = ChatMode.valueOf(
                    snapshot.path("mode").asText());
            MemoryMode memory = MemoryMode.valueOf(
                    snapshot.path("memoryMode").asText());
            String declaredModel = snapshot.path(
                    "declaredModelIdentifier").asText(null);
            if (declaredModel == null || declaredModel.isBlank()) {
                throw invalidSnapshot();
            }
            List<String> candidates = textList(
                    snapshot.path("resolvedCandidates"));
            String modelRef = candidates.isEmpty()
                    ? null
                    : candidates.getFirst();
            RetrievalOptions options = retrievalOptions(
                    snapshot.path("retrievalOptions"));
            RetrievalScope scope = retrievalScope(
                    snapshot.path("effectiveScope"));
            String domainId = snapshot.path("domainId").asText(null);
            if (domainId != null && domainId.isBlank()) {
                domainId = null;
            }
            ChatPrincipal principal = ChatPrincipal.from(httpRequest);
            ChatCommand command = new ChatCommand(
                    declaration.latestUser(),
                    sessionId,
                    principal,
                    principal.memoryConversationId(sessionId),
                    mode,
                    memory,
                    modelRef,
                    domainId,
                    scope,
                    options,
                    Map.of(),
                    declaration.inputMessages(),
                    candidates)
                    .withFilters(declaration.filters());
            return new MappedRequest(
                    declaredModel,
                    Boolean.TRUE.equals(request.getStream()),
                    command);
        } catch (RagException e) {
            throw e;
        } catch (Exception e) {
            throw invalidSnapshot();
        }
    }

    private void validateRagShape(
            OpenAiChatCompletionRequest.RagOptions rag) {
        if (rag != null && !rag.getAdditionalProperties().isEmpty()) {
            throw OpenAiProtocolException.invalid(
                    "Unsupported rag fields: "
                            + rag.getAdditionalProperties().keySet(),
                    "rag",
                    "unsupported_parameter");
        }
        if (rag != null
                && rag.getFilters() != null
                && !rag.getFilters().getAdditionalProperties().isEmpty()) {
            throw OpenAiProtocolException.invalid(
                    "Unsupported rag.filters fields: "
                            + rag.getFilters().getAdditionalProperties().keySet(),
                    "rag.filters",
                    "unsupported_parameter");
        }
        if (rag != null && rag.getScope() != null
                && !rag.getScope().getAdditionalProperties().isEmpty()) {
            throw OpenAiProtocolException.invalid(
                    "Unsupported rag.scope fields: "
                            + rag.getScope().getAdditionalProperties().keySet(),
                    "rag.scope",
                    "unsupported_parameter");
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
            RetrievalScope.CollectionFilter filter =
                    RetrievalScope.CollectionFilter.valueOf(
                            node.path("collectionFilter").asText());
            List<Long> collectionIds = longList(node.path("collectionIds"));
            List<Long> documentIds = longList(node.path("documentIds"));
            String documentType = node.path("documentType").asText(null);
            if (documentType != null && documentType.isBlank()) {
                documentType = null;
            }
            return new RetrievalScope(
                    filter,
                    collectionIds,
                    documentIds,
                    documentType,
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

    private RagException invalidSnapshot() {
        return new RagException(
                ErrorCode.IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID,
                "Chat execution snapshot is invalid");
    }

    private RetrievalFilters resolveFilters(OpenAiChatCompletionRequest.RagOptions rag) {
        if (rag == null || rag.getFilters() == null) {
            return RetrievalFilters.none();
        }
        try {
            return filterValidator.validate(
                    rag.getFilters().getMetadataContains(),
                    rag.getFilters().getPayloadContains());
        } catch (IllegalArgumentException e) {
            throw OpenAiProtocolException.invalid(
                    e.getMessage(),
                    "rag.filters",
                    "invalid_value");
        }
    }

    private void validateRequest(OpenAiChatCompletionRequest request) {
        if (request == null) {
            throw OpenAiProtocolException.invalid(
                    "Request body is required", null, "invalid_request_body");
        }
        if (request.getModel() == null || request.getModel().isBlank()) {
            throw OpenAiProtocolException.invalid(
                    "model is required", "model", "missing_required_parameter");
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw OpenAiProtocolException.invalid(
                    "messages must contain at least one message",
                    "messages",
                    "missing_required_parameter");
        }
        if (request.getMessages().size() > MAX_MESSAGES) {
            throw OpenAiProtocolException.invalid(
                    "messages must not contain more than " + MAX_MESSAGES
                            + " items",
                    "messages",
                    "invalid_value");
        }
        if (request.getN() != null && request.getN() != 1) {
            throw OpenAiProtocolException.invalid(
                    "Only n=1 is supported", "n", "unsupported_parameter");
        }
        Map<String, Object> unsupported = new LinkedHashMap<>();
        unsupported.put("temperature", request.getTemperature());
        unsupported.put("top_p", request.getTopP());
        unsupported.put("max_tokens", request.getMaxTokens());
        unsupported.put("max_completion_tokens",
                request.getMaxCompletionTokens());
        unsupported.put("tools", request.getTools());
        unsupported.put("tool_choice", request.getToolChoice());
        unsupported.put("functions", request.getFunctions());
        unsupported.put("function_call", request.getFunctionCall());
        unsupported.put("logprobs", request.getLogprobs());
        unsupported.put("response_format", request.getResponseFormat());
        unsupported.put("stream_options", request.getStreamOptions());
        unsupported.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .findFirst()
                .ifPresent(entry -> {
                    throw OpenAiProtocolException.invalid(
                            entry.getKey() + " is not supported by this RAG endpoint",
                            entry.getKey(),
                            "unsupported_parameter");
                });
        if (!request.getAdditionalProperties().isEmpty()) {
            String field = request.getAdditionalProperties().keySet()
                    .iterator().next();
            throw OpenAiProtocolException.invalid(
                    field + " is not supported by this RAG endpoint",
                    field,
                    "unsupported_parameter");
        }
    }

    private List<ChatInputMessage> parseMessages(
            List<OpenAiChatCompletionRequest.Message> messages) {
        List<ChatInputMessage> parsed = new ArrayList<>();
        boolean hasUser = false;
        int totalCharacters = 0;
        for (int index = 0; index < messages.size(); index++) {
            OpenAiChatCompletionRequest.Message message = messages.get(index);
            if (message == null) {
                throw invalidMessage(index, "message must not be null");
            }
            if (message.getName() != null
                    || message.getToolCalls() != null
                    || message.getFunctionCall() != null
                    || !message.getAdditionalProperties().isEmpty()) {
                throw invalidMessage(index,
                        "name, tool/function calls and unknown message fields "
                                + "are not supported");
            }
            ChatInputMessage.Role role = parseRole(message.getRole(), index);
            String content = parseTextContent(message.getContent(), index);
            totalCharacters += content.length();
            if (totalCharacters > MAX_CONTENT_CHARACTERS) {
                throw OpenAiProtocolException.invalid(
                        "Total message content exceeds "
                                + MAX_CONTENT_CHARACTERS + " characters",
                        "messages",
                        "request_too_large");
            }
            parsed.add(new ChatInputMessage(role, content));
            hasUser = hasUser || role == ChatInputMessage.Role.USER;
        }
        if (!hasUser) {
            throw OpenAiProtocolException.invalid(
                    "messages must contain at least one user message",
                    "messages",
                    "missing_user_message");
        }
        return List.copyOf(parsed);
    }

    private ChatInputMessage.Role parseRole(String role, int index) {
        if (role == null || role.isBlank()) {
            throw invalidMessage(index, "role is required");
        }
        try {
            return ChatInputMessage.Role.valueOf(
                    role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalidMessage(index,
                    "role must be system, developer, user, or assistant");
        }
    }

    private String parseTextContent(JsonNode content, int index) {
        if (content == null || content.isNull()) {
            throw invalidMessage(index, "content is required");
        }
        if (content.isTextual()) {
            return requireText(content.asText(), index);
        }
        if (!content.isArray()) {
            throw invalidMessage(index,
                    "content must be a string or an array of text parts");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            if (!part.isObject()
                    || !part.has("type")
                    || !"text".equals(part.path("type").asText())
                    || !part.path("text").isTextual()
                    || part.size() != 2) {
                throw invalidMessage(index,
                        "only {\"type\":\"text\",\"text\":\"...\"} "
                                + "content parts are supported");
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(part.path("text").asText());
        }
        return requireText(text.toString(), index);
    }

    private String requireText(String content, int index) {
        if (content == null || content.isBlank()) {
            throw invalidMessage(index, "content must not be blank");
        }
        return content;
    }

    private OpenAiProtocolException invalidMessage(
            int index, String message) {
        return OpenAiProtocolException.invalid(
                message,
                "messages[" + index + "]",
                "unsupported_message_type");
    }

    public record MappedRequest(
            String modelAlias,
            boolean stream,
            ChatCommand command) {
    }

    public record Declaration(
            String latestUser,
            List<ChatInputMessage> inputMessages,
            RetrievalFilters filters) {
    }
}
