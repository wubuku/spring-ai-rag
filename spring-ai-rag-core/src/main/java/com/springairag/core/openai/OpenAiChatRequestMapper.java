package com.springairag.core.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatInputMessage;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.RetrievalScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
        validateRequest(request);
        OpenAiChatCompletionRequest.RagOptions rag = request.getRag();
        if (rag != null && !rag.getAdditionalProperties().isEmpty()) {
            throw OpenAiProtocolException.invalid(
                    "Unsupported rag fields: "
                            + rag.getAdditionalProperties().keySet(),
                    "rag",
                    "unsupported_parameter");
        }
        OpenAiModelAliasRegistry.ResolvedAlias alias = aliasRegistry.resolve(
                request.getModel(),
                rag != null ? rag.getMode() : null,
                rag != null ? rag.getMemory() : null);
        RetrievalScope scope = scopeAdapter.resolve(rag, httpRequest);
        List<ChatInputMessage> inputMessages = parseMessages(
                request.getMessages());
        String latestUser = inputMessages.stream()
                .filter(message -> message.role() == ChatInputMessage.Role.USER)
                .reduce((left, right) -> right)
                .orElseThrow()
                .content();

        ChatPrincipal principal = ChatPrincipal.from(httpRequest);
        String sessionId = "oai-" + UUID.randomUUID()
                .toString().replace("-", "");
        RetrievalOptions retrievalOptions = RetrievalOptions.from(
                properties.getRetrieval(),
                null,
                false,
                0,
                false,
                false,
                false,
                false);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transport", "openai-chat-completions");
        metadata.put("openaiModelAlias", alias.alias());
        ChatCommand command = new ChatCommand(
                latestUser,
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
                metadata,
                inputMessages,
                alias.candidates());
        return new MappedRequest(
                alias.alias(),
                Boolean.TRUE.equals(request.getStream()),
                command);
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
}
