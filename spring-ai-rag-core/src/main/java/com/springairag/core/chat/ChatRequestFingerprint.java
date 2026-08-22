package com.springairag.core.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.core.exception.RagException;
import com.springairag.api.enums.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transport-neutral canonical request fingerprint for native Chat requests.
 */
public final class ChatRequestFingerprint {

    public static final int VERSION = 1;
    private static final int MAX_METADATA_BYTES = 32 * 1024;
    private static final List<String> CREDENTIAL_FIELDS = List.of(
            "apikey", "authorization", "token", "secret", "password",
            "rawkey", "accesstoken", "refreshtoken");

    private ChatRequestFingerprint() {
    }

    public static Result nativeRequest(
            ChatRequest request,
            ObjectMapper objectMapper) {
        if (request == null) {
            throw new IllegalArgumentException("chat request must not be null");
        }
        JsonNode metadata = objectMapper.valueToTree(
                request.getMetadata() == null ? Map.of() : request.getMetadata());
        validateMetadata(metadata, objectMapper);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", VERSION);
        root.put("transportNeutral", true);
        root.put("sessionId", request.getSessionId() == null
                || request.getSessionId().isBlank()
                ? "AUTO_SESSION"
                : request.getSessionId());
        root.put("message", request.getMessage() == null ? "" : request.getMessage());
        ArrayNode inputMessages = root.putArray("inputMessages");
        ObjectNode user = inputMessages.addObject();
        user.put("role", "user");
        user.put("content", request.getMessage() == null ? "" : request.getMessage());
        root.put("mode", request.getMode() == null
                ? ChatMode.KNOWLEDGE.name()
                : request.getMode().name());
        root.put("memoryMode", "DEFAULT");
        root.put("declaredModelIdentifier",
                blankAsDefault(request.getModel()));
        root.put("domainId", blankAsNull(request.getDomainId()));

        ObjectNode retrieval = root.putObject("retrieval");
        retrieval.put("maxResultsExplicit",
                request.isMaxResultsExplicitlySet());
        retrieval.put("maxResults", request.getMaxResults());
        retrieval.put("useHybridSearchExplicit",
                request.isUseHybridSearchExplicitlySet());
        retrieval.put("useHybridSearch", request.isUseHybridSearch());
        retrieval.put("useRerankExplicit",
                request.isUseRerankExplicitlySet());
        retrieval.put("useRerank", request.isUseRerank());
        retrieval.set("filters", canonicalize(
                objectMapper.valueToTree(request.getFilters())));

        ObjectNode scope = root.putObject("scope");
        if (request.getMode() == ChatMode.PLAIN) {
            scope.put("mode", "NOT_APPLICABLE");
        } else {
            CollectionScopeMode scopeMode = request.getCollectionScopeMode();
            scope.put("mode", scopeMode == null
                    ? inferredScopeMode(request)
                    : scopeMode.name());
            scope.set("collectionIds", sortedNumbers(
                    objectMapper, request.getCollectionIds()));
            scope.set("collectionKeys", sortedStrings(
                    objectMapper, request.getCollectionKeys()));
            scope.set("documentIds", sortedNumbers(
                    objectMapper, request.getDocumentIds()));
        }
        root.set("clientMetadata", canonicalize(metadata));
        JsonNode canonical = canonicalize(root);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            return new Result(sha256(bytes), bytes.length, canonical);
        } catch (Exception e) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                    "Idempotency request metadata cannot be canonicalized",
                    e);
        }
    }

    public static Result openAiRequest(
            OpenAiChatCompletionRequest request,
            ObjectMapper objectMapper) {
        return openAiRequest(request, objectMapper, List.of());
    }

    public static Result openAiRequest(
            OpenAiChatCompletionRequest request,
            ObjectMapper objectMapper,
            List<String> collectionHeaderValues) {
        if (request == null) {
            throw new IllegalArgumentException("OpenAI request must not be null");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", VERSION);
        root.put("transportNeutral", true);
        root.put("sessionId", "AUTO_SESSION");
        ArrayNode messages = root.putArray("inputMessages");
        if (request.getMessages() != null) {
            for (OpenAiChatCompletionRequest.Message message
                    : request.getMessages()) {
                ObjectNode item = messages.addObject();
                item.put("role", message == null || message.getRole() == null
                        ? ""
                        : message.getRole().trim().toLowerCase());
                item.set("content", message == null
                        ? objectMapper.nullNode()
                        : canonicalize(message.getContent()));
            }
        }
        String mode = request.getRag() == null
                || request.getRag().getMode() == null
                ? ChatMode.KNOWLEDGE.name()
                : request.getRag().getMode().name();
        root.put("mode", mode);
        root.put("memoryMode", request.getRag() == null
                || request.getRag().getMemory() == null
                || request.getRag().getMemory().isBlank()
                ? "DEFAULT"
                : request.getRag().getMemory().trim().toUpperCase());
        root.put("declaredModelIdentifier",
                request.getModel() == null ? "DEFAULT" : request.getModel());
        root.putNull("domainId");
        ObjectNode retrieval = root.putObject("retrieval");
        if (request.getRag() != null) {
            retrieval.set("filters", canonicalize(objectMapper.valueToTree(
                    request.getRag().getFilters())));
            retrieval.set("documentIds", sortedNumbers(
                    objectMapper, request.getRag().getDocumentIds()));
        }
        ObjectNode scope = root.putObject("scope");
        ArrayNode normalizedHeaderKeys = sortedHeaderStrings(
                objectMapper, collectionHeaderValues);
        if (ChatMode.PLAIN.name().equals(mode)) {
            if ((request.getRag() != null
                    && (request.getRag().getScope() != null
                    || request.getRag().getDocumentIds() != null))
                    || !normalizedHeaderKeys.isEmpty()) {
                throw new RagException(
                        ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                        "Retrieval scope is not allowed in PLAIN mode");
            }
            scope.put("mode", "NOT_APPLICABLE");
        } else if (!normalizedHeaderKeys.isEmpty()) {
            scope.put("mode", CollectionScopeMode.SELECTED_COLLECTIONS.name());
        } else if (request.getRag() != null
                && request.getRag().getScope() != null) {
            var declared = request.getRag().getScope();
            scope.put("mode", declared.getMode() == null
                    ? CollectionScopeMode.CALLER_VISIBLE.name()
                    : declared.getMode().name());
            scope.set("collectionIds", sortedNumbers(
                    objectMapper, declared.getCollectionIds()));
            scope.set("collectionKeys", sortedStrings(
                    objectMapper, declared.getCollectionKeys()));
        } else {
            scope.put("mode", CollectionScopeMode.CALLER_VISIBLE.name());
        }
        scope.set("collectionKeyHeader", normalizedHeaderKeys);
        root.set("clientMetadata", objectMapper.createObjectNode());
        JsonNode canonical = canonicalize(root);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            return new Result(sha256(bytes), bytes.length, canonical);
        } catch (Exception e) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                    "OpenAI request cannot be canonicalized",
                    e);
        }
    }

    public record Result(String sha256, int canonicalBytes, JsonNode canonical) {
    }

    private static String inferredScopeMode(ChatRequest request) {
        if (request.getCollectionIds() != null
                || request.getCollectionKeys() != null
                || request.getDocumentIds() != null) {
            return CollectionScopeMode.SELECTED_COLLECTIONS.name();
        }
        return CollectionScopeMode.CALLER_VISIBLE.name();
    }

    private static String blankAsDefault(String value) {
        return value == null || value.isBlank() ? "DEFAULT" : value;
    }

    private static String blankAsNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ArrayNode sortedNumbers(ObjectMapper mapper, List<Long> values) {
        ArrayNode array = mapper.createArrayNode();
        if (values == null) {
            return array;
        }
        values.stream()
                .filter(value -> value != null)
                .distinct()
                .sorted()
                .forEach(array::add);
        return array;
    }

    private static ArrayNode sortedStrings(ObjectMapper mapper, List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        if (values == null) {
            return array;
        }
        values.stream()
                .filter(value -> value != null)
                .distinct()
                .sorted()
                .forEach(array::add);
        return array;
    }

    private static ArrayNode sortedHeaderStrings(
            ObjectMapper mapper,
            List<String> values) {
        if (values == null || values.isEmpty()) {
            return mapper.createArrayNode();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.contains(",")) {
                throw new RagException(
                        ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                        "X-RAG-Collection-Key must contain one non-blank key per header");
            }
            normalized.add(value.trim());
        }
        return sortedStrings(mapper, normalized);
    }

    private static void validateMetadata(JsonNode node, ObjectMapper mapper) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(node);
            if (bytes.length > MAX_METADATA_BYTES) {
                throw new RagException(
                        ErrorCode.IDEMPOTENCY_REQUEST_TOO_LARGE,
                        "clientMetadata exceeds 32768 bytes");
            }
        } catch (RagException e) {
            throw e;
        } catch (Exception e) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                    "clientMetadata is not valid JSON", e);
        }
        validateNode(node);
    }

    private static void validateNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String value = node.textValue();
            for (int index = 0; index < value.length(); index++) {
                if (Character.isISOControl(value.charAt(index))) {
                    throw new RagException(
                            ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                            "clientMetadata contains control characters");
                }
            }
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (CREDENTIAL_FIELDS.contains(field.getKey().toLowerCase())) {
                    throw new RagException(
                            ErrorCode.IDEMPOTENCY_REQUEST_METADATA_INVALID,
                            "clientMetadata contains a credential field");
                }
                validateNode(field.getValue());
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(ChatRequestFingerprint::validateNode);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            ArrayNode result = array.arrayNode();
            array.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        ObjectNode object = (ObjectNode) node;
        ObjectNode result = object.objectNode();
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        names.sort(Comparator.naturalOrder());
        for (String name : names) {
            result.set(name, canonicalize(object.get(name)));
        }
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
