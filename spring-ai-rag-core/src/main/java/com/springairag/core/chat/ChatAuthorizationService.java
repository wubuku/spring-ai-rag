package com.springairag.core.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.dto.ChatSource;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.security.ApiKeyCollectionAccess;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds and verifies the durable authorization evidence for a Chat turn.
 *
 * <p>The response snapshot is immutable, but its right to be replayed is not:
 * Collection ACLs and document lifecycle state are checked again for every
 * replay. This service deliberately fails closed when a source cannot be
 * mapped to an authoritative document row.</p>
 */
@Service
public class ChatAuthorizationService {

    private static final int VERSION = 1;

    private final ObjectMapper objectMapper;
    private final RagDocumentRepository documentRepository;
    private final RagApiKeyRepository apiKeyRepository;

    public ChatAuthorizationService(
            ObjectMapper objectMapper,
            RagDocumentRepository documentRepository,
            RagApiKeyRepository apiKeyRepository) {
        this.objectMapper = objectMapper;
        this.documentRepository = documentRepository;
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * Creates the initial evidence row before the provider is called.
     */
    public String initialSnapshot(ChatCommand command) {
        return snapshot(command, null);
    }

    /**
     * Adds authoritative source document mappings after execution.
     */
    public String snapshot(ChatCommand command, ChatResponse response) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("authorizationSnapshotVersion", VERSION);
            if (command.mode() == ChatMode.PLAIN) {
                snapshot.put("scopeMode", "NOT_APPLICABLE");
                snapshot.put("callerAccessMode", "NOT_APPLICABLE");
                snapshot.put("effectiveSelectedCollectionIds", List.of());
                snapshot.put("callerAllowList", List.of());
                snapshot.put("unassignedDocumentsAllowed", false);
            } else {
                String scopeMode = switch (command.retrievalScope().collectionFilter()) {
                    case ANY_ASSIGNED -> "ANY_COLLECTION";
                    case SELECTED -> "SELECTED_COLLECTIONS";
                    case NONE -> "CALLER_VISIBLE";
                };
                RagApiKey key = ApiKeyCollectionAccess.currentKey();
                boolean unrestricted = ApiKeyCollectionAccess.isUnrestricted(key);
                snapshot.put("scopeMode", scopeMode);
                snapshot.put(
                        "callerAccessMode",
                        unrestricted ? "UNRESTRICTED" : "RESTRICTED");
                snapshot.put(
                        "effectiveSelectedCollectionIds",
                        sortedLongs(command.retrievalScope().collectionIds()));
                snapshot.put(
                        "callerAllowList",
                        unrestricted
                                ? List.of()
                                : sortedLongs(new ArrayList<>(
                                        ApiKeyCollectionAccess
                                                .restrictedCollectionIds(key)
                                                .orElseThrow())));
                snapshot.put(
                        "unassignedDocumentsAllowed",
                        "CALLER_VISIBLE".equals(scopeMode) && unrestricted);
            }
            snapshot.put(
                    "sourceDocumentCollectionSnapshot",
                    sourceSnapshot(response));
            snapshot.put(
                    "sourceCollectionIdsObserved",
                    observedCollectionIds(snapshot));
            String json = objectMapper.writeValueAsString(snapshot);
            validateSnapshotJson(json);
            return json;
        } catch (RagException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid(e);
        } catch (Exception e) {
            throw invalid(e);
        }
    }

    /**
     * Verifies current principal access and the current enabled/source state.
     */
    public void verifyReplay(
            ChatTurnOperation operation,
            ChatPrincipal principal) {
        if (operation == null || operation.authorizationScopeSnapshot() == null) {
            throw forbidden("Chat authorization snapshot is missing");
        }
        try {
            JsonNode snapshot = objectMapper.readTree(
                    operation.authorizationScopeSnapshot());
            if (snapshot == null
                    || snapshot.path("authorizationSnapshotVersion").asInt()
                    != VERSION) {
                throw invalid(null);
            }
            ValidatedSnapshot validated = validateSnapshot(snapshot);
            if ("NOT_APPLICABLE".equals(validated.scopeMode())) {
                return;
            }

            RagApiKey currentKey = currentKey(principal);
            boolean currentUnrestricted =
                    ApiKeyCollectionAccess.isUnrestricted(currentKey);
            String firstAccess = validated.callerAccessMode();
            List<Long> firstAllow = validated.callerAllowList();
            List<Long> selected = validated.selectedCollectionIds();
            String scopeMode = validated.scopeMode();
            boolean unassignedAllowed = validated.unassignedDocumentsAllowed();

            if ("CALLER_VISIBLE".equals(scopeMode)
                    && "UNRESTRICTED".equals(firstAccess)
                    && !currentUnrestricted) {
                throw forbidden("Chat replay authorization became narrower");
            }
            if ("RESTRICTED".equals(firstAccess)) {
                if (currentUnrestricted) {
                    // Widening is harmless for a previously bounded answer.
                } else if (!containsAll(currentAllowList(currentKey), firstAllow)) {
                    throw forbidden("Chat replay collection access was revoked");
                }
            }
            if ("UNRESTRICTED".equals(firstAccess)
                    && !currentUnrestricted
                    && ("ANY_COLLECTION".equals(scopeMode)
                    || "SELECTED_COLLECTIONS".equals(scopeMode))) {
                if ("ANY_COLLECTION".equals(scopeMode)
                        || !containsAll(currentAllowList(currentKey), selected)) {
                    throw forbidden("Chat replay collection access was narrowed");
                }
            }
            verifySources(
                    validated.sources(),
                    scopeMode,
                    selected,
                    currentKey,
                    currentUnrestricted,
                    unassignedAllowed);
        } catch (RagException e) {
            throw e;
        } catch (Exception e) {
            throw forbidden("Chat replay authorization could not be verified");
        }
    }

    private ValidatedSnapshot validateSnapshot(JsonNode snapshot) {
        String scopeMode = requiredEnum(
                snapshot,
                "scopeMode",
                Set.of(
                        "NOT_APPLICABLE",
                        "CALLER_VISIBLE",
                        "ANY_COLLECTION",
                        "SELECTED_COLLECTIONS"));
        String callerAccessMode = requiredEnum(
                snapshot,
                "callerAccessMode",
                Set.of("NOT_APPLICABLE", "UNRESTRICTED", "RESTRICTED"));
        List<Long> selectedCollectionIds = longList(
                snapshot.get("effectiveSelectedCollectionIds"));
        List<Long> callerAllowList = longList(
                snapshot.get("callerAllowList"));
        JsonNode unassignedNode = snapshot.get("unassignedDocumentsAllowed");
        if (unassignedNode == null || !unassignedNode.isBoolean()) {
            throw invalid(null);
        }
        boolean unassignedAllowed = unassignedNode.booleanValue();
        List<SourceEvidence> sources = sourceEvidence(snapshot);
        List<Long> observed = longList(
                snapshot.get("sourceCollectionIdsObserved"));
        List<Long> derived = sources.stream()
                .map(SourceEvidence::collectionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (!derived.equals(observed)) {
            throw invalid(null);
        }
        if ("NOT_APPLICABLE".equals(scopeMode)) {
            if (!"NOT_APPLICABLE".equals(callerAccessMode)
                    || !selectedCollectionIds.isEmpty()
                    || !callerAllowList.isEmpty()
                    || unassignedAllowed
                    || !sources.isEmpty()
                    || !observed.isEmpty()) {
                throw invalid(null);
            }
        } else if ("NOT_APPLICABLE".equals(callerAccessMode)) {
            throw invalid(null);
        }
        return new ValidatedSnapshot(
                scopeMode,
                callerAccessMode,
                selectedCollectionIds,
                callerAllowList,
                unassignedAllowed,
                sources);
    }

    private String requiredEnum(
            JsonNode snapshot,
            String field,
            Set<String> allowed) {
        JsonNode value = snapshot.get(field);
        if (value == null || !value.isTextual()
                || !allowed.contains(value.asText())) {
            throw invalid(null);
        }
        return value.asText();
    }

    private RagApiKey currentKey(ChatPrincipal principal) {
        if (principal == null || !principal.id().startsWith("db:")) {
            return null;
        }
        String keyId = principal.id().substring("db:".length());
        return apiKeyRepository.findByKeyId(keyId).orElse(null);
    }

    private void verifySources(
            List<SourceEvidence> sources,
            String scopeMode,
            List<Long> selected,
            RagApiKey currentKey,
            boolean unrestricted,
            boolean unassignedAllowed) {
        List<Long> currentAllow = currentAllowList(currentKey);
        for (SourceEvidence source : sources) {
            RagDocument document = documentRepository.findById(source.documentId())
                    .orElseThrow(() -> forbidden("Chat source document no longer exists"));
            if (!Boolean.TRUE.equals(document.getEnabled())
                    || document.getSourceDeletedAt() != null) {
                throw forbidden("Chat source document is disabled or tombstoned");
            }
            Long currentCollection = document.getCollectionId();
            if (!java.util.Objects.equals(
                    currentCollection, source.collectionId())) {
                throw forbidden("Chat source Collection changed");
            }
            if (currentCollection == null) {
                if (!("CALLER_VISIBLE".equals(scopeMode)
                        && unassignedAllowed && unrestricted)) {
                    throw forbidden("Chat source document is not assigned");
                }
                continue;
            }
            if (!unrestricted && !currentAllow.contains(currentCollection)) {
                throw forbidden("Chat source Collection is not authorized");
            }
            if ("SELECTED_COLLECTIONS".equals(scopeMode)
                    && !selected.contains(currentCollection)) {
                throw forbidden("Chat source escaped the selected scope");
            }
            if ("ANY_COLLECTION".equals(scopeMode)
                    && currentCollection <= 0) {
                throw forbidden("Chat source Collection is invalid");
            }
        }
    }

    private List<Map<String, Object>> sourceSnapshot(ChatResponse response) {
        if (response == null || response.getSources() == null) {
            return List.of();
        }
        Map<Long, Map<String, Object>> unique = new LinkedHashMap<>();
        for (ChatSource source : response.getSources()) {
            Long documentId = parseDocumentId(source);
            if (documentId == null) {
                throw invalid(null);
            }
            RagDocument document = documentRepository.findById(documentId)
                    .orElseThrow(() -> invalid(null));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("documentId", documentId);
            entry.put("collectionId", document.getCollectionId());
            unique.putIfAbsent(documentId, entry);
        }
        return unique.values().stream().toList();
    }

    private List<Long> observedCollectionIds(Map<String, Object> snapshot) {
        Object raw = snapshot.get("sourceDocumentCollectionSnapshot");
        if (!(raw instanceof List<?> rows)) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map
                    && map.get("collectionId") instanceof Number number) {
                ids.add(number.longValue());
            }
        }
        return ids.stream().sorted().toList();
    }

    private List<SourceEvidence> sourceEvidence(JsonNode snapshot) {
        List<SourceEvidence> result = new ArrayList<>();
        JsonNode rows = snapshot.get("sourceDocumentCollectionSnapshot");
        if (rows == null || !rows.isArray()) {
            throw invalid(null);
        }
        for (JsonNode row : rows) {
            if (row == null || !row.isObject()) {
                throw invalid(null);
            }
            JsonNode documentId = row.get("documentId");
            if (documentId == null
                    || !documentId.isIntegralNumber()
                    || documentId.longValue() <= 0) {
                throw invalid(null);
            }
            JsonNode collection = row.get("collectionId");
            Long collectionId;
            if (collection == null || collection.isNull()) {
                collectionId = null;
            } else if (collection.isIntegralNumber()
                    && collection.longValue() > 0) {
                collectionId = collection.longValue();
            } else {
                throw invalid(null);
            }
            result.add(new SourceEvidence(
                    documentId.longValue(), collectionId));
        }
        if (result.stream()
                .map(SourceEvidence::documentId)
                .distinct()
                .count() != result.size()) {
            throw invalid(null);
        }
        return result;
    }

    private Long parseDocumentId(ChatSource source) {
        if (source == null || source.getDocumentId() == null) {
            return null;
        }
        try {
            long value = Long.parseLong(source.getDocumentId());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<Long> currentAllowList(RagApiKey key) {
        return key == null || ApiKeyCollectionAccess.isUnrestricted(key)
                ? List.of()
                : sortedLongs(new ArrayList<>(
                        ApiKeyCollectionAccess.restrictedCollectionIds(key)
                                .orElseThrow()));
    }

    private boolean containsAll(List<Long> container, List<Long> required) {
        return container.containsAll(required);
    }

    private List<Long> longList(JsonNode value) {
        if (!value.isArray()) {
            throw invalid(null);
        }
        List<Long> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isIntegralNumber() || item.longValue() <= 0) {
                throw invalid(null);
            }
            result.add(item.longValue());
        }
        return sortedLongs(result);
    }

    private List<Long> sortedLongs(List<Long> values) {
        return values == null
                ? List.of()
                : values.stream().filter(v -> v != null && v > 0)
                .distinct().sorted(Comparator.naturalOrder()).toList();
    }

    private void validateSnapshotJson(String json) {
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new RagException(
                    ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                    "Chat authorization snapshot exceeds configured size");
        }
    }

    private RagException invalid(Throwable cause) {
        return cause == null
                ? new RagException(
                        ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                        "Chat authorization snapshot is invalid")
                : new RagException(
                        ErrorCode.IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID,
                        "Chat authorization snapshot is invalid",
                        cause);
    }

    private RagException forbidden(String message) {
        return new RagException(ErrorCode.FORBIDDEN, message);
    }

    private record SourceEvidence(long documentId, Long collectionId) {
    }

    private record ValidatedSnapshot(
            String scopeMode,
            String callerAccessMode,
            List<Long> selectedCollectionIds,
            List<Long> callerAllowList,
            boolean unassignedDocumentsAllowed,
            List<SourceEvidence> sources) {
    }
}
