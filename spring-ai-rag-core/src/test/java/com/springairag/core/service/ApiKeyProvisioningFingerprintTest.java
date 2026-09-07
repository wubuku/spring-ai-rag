package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreateRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 供给幂等指纹：语义规范化、确定性 SHA-256 与角色隔离。 */
class ApiKeyProvisioningFingerprintTest {

    private static ApiKeyCreateRequest request() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest(
                "provisioned-key",
                LocalDateTime.of(2027, 1, 1, 0, 0));
        request.setAllowedCollectionIds(List.of(3L, 1L, 1L));
        request.setCapabilities(List.of("RAG_READ", "RAG_WRITE"));
        return request;
    }

    @Test
    void producesDeterministic64CharacterHexDigests() {
        String first = ApiKeyProvisioningFingerprint.sha256(request(), "NORMAL");
        String second = ApiKeyProvisioningFingerprint.sha256(request(), "NORMAL");

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void differentRolesProduceDifferentFingerprints() {
        String normal = ApiKeyProvisioningFingerprint.sha256(request(), "NORMAL");
        String admin = ApiKeyProvisioningFingerprint.sha256(request(), "ADMIN");

        assertNotEquals(normal, admin);
    }

    @Test
    void differentNamesProduceDifferentFingerprints() {
        ApiKeyCreateRequest other = request();
        other.setName("other-key");

        assertNotEquals(
                ApiKeyProvisioningFingerprint.sha256(request(), "NORMAL"),
                ApiKeyProvisioningFingerprint.sha256(other, "NORMAL"));
    }

    @Test
    void canonicalJsonNormalizesCollectionsCapabilitiesAndDates() {
        String canonical = ApiKeyProvisioningFingerprint.canonicalJson(
                request(), "NORMAL");

        // 日期经 JavaTimeModule 序列化为 ISO 格式。
        assertTrue(canonical.contains("\"expiresAt\":\"2027-01-01T00:00:00\""));
        // 集合 ID 去重并按稳定顺序序列化。
        assertTrue(canonical.contains("\"allowedCollectionIds\":[1,3]"));
        assertTrue(canonical.contains("\"capabilities\":[\"RAG_READ\",\"RAG_WRITE\"]"));
    }

    @Test
    void canonicalJsonTreatsEmptyCollectionsAsNull() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest(
                "unscoped-key",
                LocalDateTime.of(2027, 1, 1, 0, 0));

        String canonical = ApiKeyProvisioningFingerprint.canonicalJson(
                request, "NORMAL");

        assertTrue(canonical.contains("\"allowedCollectionIds\":null"));
    }
}
