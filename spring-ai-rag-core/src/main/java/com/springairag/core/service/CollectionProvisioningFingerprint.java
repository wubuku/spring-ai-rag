package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.springairag.api.dto.CollectionRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 构建 Collection 创建请求的稳定语义指纹。
 */
public final class CollectionProvisioningFingerprint {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private CollectionProvisioningFingerprint() {
    }

    public static String sha256(CollectionRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(request).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    static String canonicalJson(CollectionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ObjectNode semantic = MAPPER.createObjectNode();
        putNullableText(semantic, "collectionKey", request.getCollectionKey());
        putNullableText(semantic, "name", request.getName());
        putNullableText(semantic, "description", request.getDescription());
        putNullableText(semantic, "embeddingModel", request.getEmbeddingModel());
        semantic.put("dimensions",
                request.getDimensions() == null ? 1024 : request.getDimensions());
        semantic.put("enabled",
                request.getEnabled() == null || request.getEnabled());
        semantic.set("metadata", canonicalize(MAPPER.valueToTree(request.getMetadata())));
        try {
            return MAPPER.writeValueAsString(semantic);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Unable to build Collection provisioning fingerprint", error);
        }
    }

    private static void putNullableText(ObjectNode target, String field, String value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return MAPPER.nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            fields.forEachRemaining(entry ->
                    sorted.put(entry.getKey(), canonicalize(entry.getValue())));
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(value -> out.add(canonicalize(value)));
            return out;
        }
        if (node.isNumber()) {
            return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        }
        return node.deepCopy();
    }
}
