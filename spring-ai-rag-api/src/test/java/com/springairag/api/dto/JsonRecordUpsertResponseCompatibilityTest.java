package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.CollectionAccessMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP-2 Wire 兼容门禁：新增字段全部可空/可省略，旧构造器保持可用。
 */
class JsonRecordUpsertResponseCompatibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void principalOldConstructorsStayCompatibleAndNewTenantFieldsAreOptional()
            throws Exception {
        IntegrationCapabilitiesResponse.Principal empty =
                new IntegrationCapabilitiesResponse.Principal();
        assertNull(empty.getPrincipalType());
        assertNull(empty.getTenantId());

        IntegrationCapabilitiesResponse.Principal legacy =
                new IntegrationCapabilitiesResponse.Principal(
                        "DATABASE_API_KEY", "NORMAL", List.of("RAG_READ"),
                        CollectionAccessMode.RESTRICTED, List.of("tenant:manual:v1"));
        assertEquals("DATABASE_API_KEY", legacy.getPrincipalType());
        assertEquals("NORMAL", legacy.getPrincipalRole());
        assertEquals(List.of("RAG_READ"), legacy.getCapabilities());
        assertEquals(CollectionAccessMode.RESTRICTED,
                legacy.getCollectionAccessMode());
        assertEquals(List.of("tenant:manual:v1"), legacy.getAllowedCollectionKeys());
        assertNull(legacy.getTenantId());
        assertNull(legacy.getTenantMode());
        assertNull(legacy.getTenantStatus());

        JsonNode legacyJson = MAPPER.readTree(MAPPER.writeValueAsString(legacy));
        assertTrue(legacyJson.has("principalType"));
        assertTrue(legacyJson.has("allowedCollectionKeys"));
        assertFalse(legacyJson.has("tenantId"));
        assertFalse(legacyJson.has("tenantMode"));
        assertFalse(legacyJson.has("tenantStatus"));

        legacy.setTenantId("scenemill-prod");
        legacy.setTenantMode("STRICT");
        legacy.setTenantStatus("ACTIVE");
        JsonNode extendedJson = MAPPER.valueToTree(legacy);
        assertEquals("scenemill-prod", extendedJson.get("tenantId").asText());
        assertEquals("STRICT", extendedJson.get("tenantMode").asText());
        assertEquals("ACTIVE", extendedJson.get("tenantStatus").asText());
    }

    @Test
    void principalLegacySettersRemainWritableForOldConsumers() {
        IntegrationCapabilitiesResponse.Principal p =
                new IntegrationCapabilitiesResponse.Principal();
        p.setPrincipalType("ENVIRONMENT_ROOT");
        p.setPrincipalRole("ADMIN");
        p.setCapabilities(List.of("RAG_READ", "RAG_WRITE"));
        p.setCollectionAccessMode(CollectionAccessMode.UNRESTRICTED);
        p.setAllowedCollectionKeys(List.of("kb-a", "kb-b"));

        assertEquals("ENVIRONMENT_ROOT", p.getPrincipalType());
        assertEquals("ADMIN", p.getPrincipalRole());
        assertEquals(List.of("RAG_READ", "RAG_WRITE"), p.getCapabilities());
        assertEquals(CollectionAccessMode.UNRESTRICTED, p.getCollectionAccessMode());
        assertEquals(List.of("kb-a", "kb-b"), p.getAllowedCollectionKeys());
    }

    @Test
    void upsertResponseOldConstructorsDelegateWithNullContentHash() {
        JsonRecordUpsertResponse legacyShort = new JsonRecordUpsertResponse(
                1L, 2L, "kb", "ext", "CREATED", true, false, 1,
                "COMPLETED", "bge-m3", null);
        assertNull(legacyShort.contentHash());
        assertNull(legacyShort.embeddingAction());
        assertNull(legacyShort.documentRevision());
        JsonNode legacyShortJson = MAPPER.valueToTree(legacyShort);
        assertTrue(legacyShortJson.has("documentId"));
        assertTrue(legacyShortJson.has("action"));

        JsonRecordUpsertResponse legacyLegacy = new JsonRecordUpsertResponse(
                1L, 2L, "ext", "CREATED", true, false, 1,
                "COMPLETED", "bge-m3", null);
        assertNull(legacyLegacy.collectionKey());
        assertNull(legacyLegacy.contentHash());

        JsonRecordUpsertResponse legacyFull = new JsonRecordUpsertResponse(
                1L, 2L, "kb", "ext", "CREATED", true, false, 1,
                "COMPLETED", "bge-m3", null, "NONE",
                UUID.randomUUID(), UUID.randomUUID(),
                "default", "rev-1", 3L, null);
        assertNull(legacyFull.contentHash());
        assertEquals("default", legacyFull.sourceNamespace());
        assertEquals(18, JsonRecordUpsertResponse.class.getRecordComponents().length - 1);

        JsonRecordUpsertResponse extended = new JsonRecordUpsertResponse(
                1L, 2L, "kb", "ext", "CREATED", true, false, 1,
                "COMPLETED", "bge-m3", null, "NONE",
                null, null, "default", "rev-1", 3L, null, "sha256:abc");
        assertEquals("sha256:abc", extended.contentHash());
    }

    @Test
    void fourteenArgumentConstructorStillCompilesAndSerializes() throws Exception {
        JsonRecordUpsertResponse legacy = new JsonRecordUpsertResponse(
                1L, 2L, "kb", "ext", "CREATED", true, false, 1,
                "COMPLETED", "bge-m3", null, "NONE",
                UUID.randomUUID(), UUID.randomUUID());
        assertNull(legacy.contentHash());
        assertNull(legacy.sourceNamespace());
        JsonNode json = MAPPER.valueToTree(legacy);
        assertTrue(json.has("documentId"));
        assertTrue(json.has("contentHash"), "record 主构造器始终序列化该组件；旧客户端应忽略 null");
        assertTrue(json.get("contentHash").isNull());

        // 19 参主构造器与 18 参兼容构造器并存：record 组件数只增加一。
        assertEquals(19, JsonRecordUpsertResponse.class.getRecordComponents().length);
    }
}
