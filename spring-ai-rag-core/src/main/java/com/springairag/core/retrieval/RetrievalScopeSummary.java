package com.springairag.core.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.core.config.EmbeddingProfile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 授权后的 scope/filter 摘要。不保存 filter 值。
 */
public final class RetrievalScopeSummary {

    public static final int MAX_SELECTED_KEYS = 100;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RetrievalScopeSummary() {
    }

    public static Map<String, Object> from(
            CollectionScopeMode requestedMode,
            RetrievalScope scope,
            List<String> selectedCollectionKeys,
            RetrievalFilters filters,
            EmbeddingProfile profile) {
        RetrievalScope effective = scope != null ? scope : RetrievalScope.unscoped();
        RetrievalFilters effectiveFilters = filters != null ? filters : RetrievalFilters.none();
        Map<String, Object> summary = new LinkedHashMap<>();
        CollectionScopeMode mode = requestedMode != null
                ? requestedMode
                : inferMode(effective);
        summary.put("collectionScopeMode", mode.name());
        summary.put("matchNone", effective.matchNone());
        if (effective.matchNone()) {
            summary.put("collectionCount", 0);
        } else if (effective.collectionFilter() == RetrievalScope.CollectionFilter.SELECTED) {
            summary.put("collectionCount", effective.collectionIds().size());
        }
        if (mode == CollectionScopeMode.SELECTED_COLLECTIONS
                && selectedCollectionKeys != null
                && !selectedCollectionKeys.isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (String key : selectedCollectionKeys) {
                if (key != null && !key.isBlank() && keys.size() < MAX_SELECTED_KEYS) {
                    keys.add(key);
                }
            }
            summary.put("collectionKeys", List.copyOf(keys));
        }
        summary.put("documentIdCount", effective.documentIds().size());
        if (effective.documentType() != null) {
            summary.put("documentType", effective.documentType());
        }
        if (profile != null) {
            summary.put("embeddingProfileKey", profile.profileKey());
        }
        summary.put("filter", filterSummary(effectiveFilters));
        return Collections.unmodifiableMap(summary);
    }

    public static Map<String, Object> filterSummary(RetrievalFilters filters) {
        RetrievalFilters effective = filters != null ? filters : RetrievalFilters.none();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("metadataContains", containmentSummary(effective.metadataContains()));
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (JsonbContainmentFilter filter : effective.payloadContainsAll()) {
            payloads.add(containmentSummary(filter));
        }
        summary.put("payloadContains", payloads);
        return Map.copyOf(summary);
    }

    private static Map<String, Object> containmentSummary(JsonbContainmentFilter filter) {
        Map<String, Object> summary = new LinkedHashMap<>();
        boolean present = filter != null;
        summary.put("present", present);
        if (!present) {
            summary.put("canonicalBytes", 0);
            summary.put("topLevelKeyCount", 0);
            return summary;
        }
        byte[] bytes = filter.canonicalJson().getBytes(StandardCharsets.UTF_8);
        summary.put("canonicalBytes", bytes.length);
        summary.put("topLevelKeyCount", countTopLevelKeys(filter.canonicalJson()));
        return Map.copyOf(summary);
    }

    static int countTopLevelKeys(String canonicalJson) {
        if (canonicalJson == null || canonicalJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(canonicalJson);
            return root != null && root.isObject() ? root.size() : 0;
        } catch (JsonProcessingException ignored) {
            return 0;
        }
    }

    private static CollectionScopeMode inferMode(RetrievalScope scope) {
        return switch (scope.collectionFilter()) {
            case ANY_ASSIGNED -> CollectionScopeMode.ANY_COLLECTION;
            case SELECTED -> CollectionScopeMode.SELECTED_COLLECTIONS;
            case NONE -> CollectionScopeMode.CALLER_VISIBLE;
        };
    }
}
