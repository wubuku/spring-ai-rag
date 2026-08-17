package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.retrieval.RetrievalScope;

import java.util.List;
import java.util.Map;

/**
 * Immutable, server-owned input for one chat turn.
 */
public record ChatCommand(
        String message,
        String sessionId,
        ChatPrincipal principal,
        String memoryConversationId,
        ChatMode mode,
        MemoryMode memoryMode,
        String modelRef,
        String domainId,
        RetrievalScope retrievalScope,
        RetrievalOptions retrievalOptions,
        Map<String, Object> clientMetadata,
        List<ChatInputMessage> inputMessages,
        List<String> modelCandidates) {

    public ChatCommand {
        message = message != null ? message : "";
        sessionId = SessionIdValidator.resolve(sessionId);
        principal = principal != null ? principal : ChatPrincipal.local();
        memoryMode = memoryMode != null ? memoryMode : MemoryMode.SERVER;
        mode = mode != null ? mode : ChatMode.KNOWLEDGE;
        retrievalScope = retrievalScope != null ? retrievalScope : RetrievalScope.unscoped();
        retrievalOptions = retrievalOptions != null
                ? retrievalOptions
                : new RetrievalOptions(5, 0.3, true, true, 0.5, 0.5);
        clientMetadata = clientMetadata == null ? Map.of() : Map.copyOf(clientMetadata);
        inputMessages = inputMessages == null ? List.of() : List.copyOf(inputMessages);
        modelCandidates = normalizeModelCandidates(modelCandidates);
        memoryConversationId = memoryConversationId != null && !memoryConversationId.isBlank()
                ? memoryConversationId
                : principal.memoryConversationId(sessionId);
        if (modelRef != null && modelRef.isBlank()) {
            modelRef = null;
        }
    }

    /**
     * 保留现有 Java 调用方的构造签名。
     */
    public ChatCommand(
            String message,
            String sessionId,
            ChatPrincipal principal,
            String memoryConversationId,
            ChatMode mode,
            MemoryMode memoryMode,
            String modelRef,
            String domainId,
            RetrievalScope retrievalScope,
            RetrievalOptions retrievalOptions,
            Map<String, Object> clientMetadata) {
        this(message, sessionId, principal, memoryConversationId, mode, memoryMode,
                modelRef, domainId, retrievalScope, retrievalOptions, clientMetadata,
                List.of(), List.of());
    }

    public static ChatCommand of(
            String message,
            String sessionId,
            ChatMode mode,
            String modelRef,
            RetrievalScope scope,
            RetrievalOptions options,
            Map<String, Object> metadata) {
        ChatPrincipal principal = ChatPrincipal.local();
        String validSession = SessionIdValidator.resolve(sessionId);
        return new ChatCommand(
                message,
                validSession,
                principal,
                principal.memoryConversationId(validSession),
                mode,
                MemoryMode.SERVER,
                modelRef,
                null,
                scope,
                options,
                metadata,
                List.of(),
                List.of());
    }

    private static List<String> normalizeModelCandidates(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                throw new IllegalArgumentException("model candidate must not be blank");
            }
            normalized.add(candidate.trim());
        }
        return List.copyOf(normalized);
    }
}
