package com.springairag.core.service;

import com.springairag.api.dto.CollectionRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CollectionProvisioningFingerprintTest {

    @Test
    void defaultsAndCanonicalMetadataProduceTheSameFingerprint() {
        CollectionRequest implicit = request();
        implicit.setDimensions(null);
        implicit.setEnabled(null);
        implicit.setMetadata(linkedMetadata(false));

        CollectionRequest explicit = request();
        explicit.setDimensions(1024);
        explicit.setEnabled(true);
        explicit.setMetadata(linkedMetadata(true));

        assertEquals(
                CollectionProvisioningFingerprint.sha256(implicit),
                CollectionProvisioningFingerprint.sha256(explicit));
    }

    @Test
    void normalizesJsonNumbersWithoutChangingArrayOrder() {
        CollectionRequest integer = request();
        integer.setMetadata(Map.of(
                "number", 1,
                "nested", Map.of("value", new BigDecimal("10.00")),
                "array", List.of(1, 2)));
        CollectionRequest decimal = request();
        decimal.setMetadata(Map.of(
                "array", List.of(new BigDecimal("1.0"), new BigDecimal("2.00")),
                "nested", Map.of("value", 10),
                "number", new BigDecimal("1.000")));

        assertEquals(
                CollectionProvisioningFingerprint.sha256(integer),
                CollectionProvisioningFingerprint.sha256(decimal));

        decimal.setMetadata(Map.of(
                "array", List.of(2, 1),
                "nested", Map.of("value", 10),
                "number", 1));
        assertNotEquals(
                CollectionProvisioningFingerprint.sha256(integer),
                CollectionProvisioningFingerprint.sha256(decimal));
    }

    @Test
    void preservesExactStringsAndDistinguishesNullFromEmptyMetadata() {
        CollectionRequest baseline = request();
        CollectionRequest differentCase = request();
        differentCase.setName("collection");
        assertNotEquals(
                CollectionProvisioningFingerprint.sha256(baseline),
                CollectionProvisioningFingerprint.sha256(differentCase));

        CollectionRequest nullMetadata = request();
        nullMetadata.setMetadata(null);
        CollectionRequest emptyMetadata = request();
        emptyMetadata.setMetadata(Map.of());
        assertNotEquals(
                CollectionProvisioningFingerprint.sha256(nullMetadata),
                CollectionProvisioningFingerprint.sha256(emptyMetadata));
    }

    private CollectionRequest request() {
        CollectionRequest request = new CollectionRequest();
        request.setCollectionKey("tenant:manual:v1");
        request.setName("Collection");
        request.setDescription("Description");
        request.setEmbeddingModel("BAAI/bge-m3");
        return request;
    }

    private Map<String, Object> linkedMetadata(boolean reverse) {
        Map<String, Object> nested = new LinkedHashMap<>();
        if (reverse) {
            nested.put("b", new BigDecimal("1.0"));
            nested.put("a", 2);
        } else {
            nested.put("a", new BigDecimal("2.00"));
            nested.put("b", 1);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        if (reverse) {
            root.put("z", true);
            root.put("nested", nested);
        } else {
            root.put("nested", nested);
            root.put("z", true);
        }
        return root;
    }
}
