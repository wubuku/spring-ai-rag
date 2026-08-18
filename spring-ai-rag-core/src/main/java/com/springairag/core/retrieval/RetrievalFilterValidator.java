package com.springairag.core.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.springairag.api.dto.JsonRecordSearchRequest;
import com.springairag.api.dto.RetrievalFilterRequest;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 共享的 JSONB containment 校验与 canonical 序列化。
 *
 * <p>object key 递归排序、array 顺序保留、UTF-8 无多余空白。
 */
@Component
public class RetrievalFilterValidator {

    public static final int MAX_FILTER_BYTES = 16_384;
    public static final int MAX_FILTER_DEPTH = 8;
    public static final int MAX_TOTAL_FILTER_BYTES = 32_768;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public RetrievalFilters validate(RetrievalFilterRequest request) {
        if (request == null) {
            return RetrievalFilters.none();
        }
        return validate(request.getMetadataContains(), request.getPayloadContains());
    }

    public RetrievalFilters validate(JsonNode metadataContains, JsonNode payloadContains) {
        JsonbContainmentFilter metadata = validateObject(metadataContains, "metadataContains");
        JsonbContainmentFilter payload = validateObject(payloadContains, "payloadContains");
        int total = bytesOf(metadata) + bytesOf(payload);
        if (total > MAX_TOTAL_FILTER_BYTES) {
            throw new IllegalArgumentException(
                    "filters exceed " + MAX_TOTAL_FILTER_BYTES + " bytes");
        }
        return new RetrievalFilters(
                metadata,
                payload == null ? List.of() : List.of(payload));
    }

    public RetrievalFilters fromJsonRecordRequest(JsonRecordSearchRequest request) {
        if (request == null) {
            return RetrievalFilters.none();
        }
        RetrievalFilterRequest filters = request.getFilters();
        if (filters != null
                && (request.getMetadataContains() != null
                || request.getPayloadContains() != null)) {
            throw new IllegalArgumentException(
                    "filters cannot be combined with top-level metadataContains or payloadContains");
        }
        if (filters != null) {
            return validate(filters);
        }
        return validate(request.getMetadataContains(), request.getPayloadContains());
    }

    public RetrievalFilters narrowWithPayload(
            RetrievalFilters callerFilters,
            JsonNode extraPayloadContains) {
        RetrievalFilters base = callerFilters != null ? callerFilters : RetrievalFilters.none();
        JsonbContainmentFilter extra = validateObject(extraPayloadContains, "payloadContains");
        int total = bytesOf(base.metadataContains());
        for (JsonbContainmentFilter payload : base.payloadContainsAll()) {
            total += bytesOf(payload);
        }
        total += bytesOf(extra);
        if (total > MAX_TOTAL_FILTER_BYTES) {
            throw new IllegalArgumentException(
                    "filters exceed " + MAX_TOTAL_FILTER_BYTES + " bytes");
        }
        return extra == null ? base : base.withAdditionalPayload(extra);
    }

    public JsonbContainmentFilter validateObject(JsonNode filter, String fieldName) {
        if (filter == null || filter.isNull()) {
            return null;
        }
        if (!filter.isObject()) {
            throw new IllegalArgumentException(fieldName + " must be a JSON object");
        }
        if (filter.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be an empty object");
        }
        int depth = jsonDepth(filter);
        if (depth > MAX_FILTER_DEPTH) {
            throw new IllegalArgumentException(
                    fieldName + " depth exceeds " + MAX_FILTER_DEPTH);
        }
        byte[] serialized = toCanonicalBytes(filter);
        if (serialized.length > MAX_FILTER_BYTES) {
            throw new IllegalArgumentException(
                    fieldName + " exceeds " + MAX_FILTER_BYTES + " bytes");
        }
        return new JsonbContainmentFilter(new String(serialized, StandardCharsets.UTF_8));
    }

    public static String toCanonicalJson(JsonNode node) {
        return new String(toCanonicalBytes(node), StandardCharsets.UTF_8);
    }

    public static byte[] toCanonicalBytes(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(canonicalize(node));
        } catch (Exception e) {
            throw new IllegalArgumentException("filter cannot be serialized", e);
        }
    }

    static JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return NullNode.getInstance();
        }
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                out.set(name, canonicalize(node.get(name)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                out.add(canonicalize(child));
            }
            return out;
        }
        return node;
    }

    private static int jsonDepth(JsonNode node) {
        if (node == null || !node.isContainerNode()) {
            return 0;
        }
        int childDepth = 0;
        for (JsonNode child : node) {
            childDepth = Math.max(childDepth, jsonDepth(child));
        }
        return 1 + childDepth;
    }

    private static int bytesOf(JsonbContainmentFilter filter) {
        return filter == null ? 0 : filter.canonicalJson()
                .getBytes(StandardCharsets.UTF_8).length;
    }
}
