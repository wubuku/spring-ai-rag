package com.springairag.core.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分阶段轮换响应的防缓存策略：路径识别、标记与 header 传递。 */
class ApiKeyRotationHttpPolicyTest {

    @Test
    void recognizesStagedRotationPathsOnly() {
        assertTrue(ApiKeyRotationHttpPolicy.isStagedRotationPath(
                "/api/v1/rag/api-keys/rag_p_1/rotations"));
        assertTrue(ApiKeyRotationHttpPolicy.isStagedRotationPath(
                "/api/v1/rag/api-keys/rag_p_1/rotations/rot-1/complete"));
        assertFalse(ApiKeyRotationHttpPolicy.isStagedRotationPath(null));
        assertFalse(ApiKeyRotationHttpPolicy.isStagedRotationPath(
                "/api/v1/rag/api-keys/rag_p_1"));
        assertFalse(ApiKeyRotationHttpPolicy.isStagedRotationPath(
                "/other/api-keys/rag_p_1/rotations"));
    }

    @Test
    void marksOnlySensitiveRequestsAndSetsNoStore() {
        MockHttpServletRequest sensitive = new MockHttpServletRequest(
                "POST", "/api/v1/rag/api-keys/rag_p_1/rotations");
        MockHttpServletResponse sensitiveResponse = new MockHttpServletResponse();
        ApiKeyRotationHttpPolicy.mark(sensitive, sensitiveResponse);

        assertEquals(Boolean.TRUE,
                sensitive.getAttribute(ApiKeyRotationHttpPolicy.SENSITIVE_REQUEST_ATTRIBUTE));
        assertEquals("no-store", sensitiveResponse.getHeader("Cache-Control"));
        assertTrue(ApiKeyRotationHttpPolicy.isSensitive(sensitive));

        MockHttpServletRequest plain = new MockHttpServletRequest(
                "GET", "/api/v1/rag/documents");
        MockHttpServletResponse plainResponse = new MockHttpServletResponse();
        ApiKeyRotationHttpPolicy.mark(plain, plainResponse);

        assertNull(plain.getAttribute(ApiKeyRotationHttpPolicy.SENSITIVE_REQUEST_ATTRIBUTE));
        assertNull(plainResponse.getHeader("Cache-Control"));
        assertFalse(ApiKeyRotationHttpPolicy.isSensitive(plain));
    }

    @Test
    void treatsPathBasedSensitivityWithoutMarking() {
        // 中间件漏标时按路径兜底，避免敏感响应带缓存头缺省。
        MockHttpServletRequest unmarked = new MockHttpServletRequest(
                "GET", "/api/v1/rag/api-keys/rag_p_1/rotations/rot-1");
        assertTrue(ApiKeyRotationHttpPolicy.isSensitive(unmarked));
    }

    @Test
    void appliesNoStoreCacheControlOnlyToSensitiveRequests() {
        MockHttpServletRequest sensitive = new MockHttpServletRequest(
                "GET", "/api/v1/rag/api-keys/rag_p_1/rotations");
        ResponseEntity<String> built = ApiKeyRotationHttpPolicy
                .apply(ResponseEntity.ok(), sensitive)
                .body("payload");
        assertEquals(
                CacheControl.noStore().getHeaderValue(),
                built.getHeaders().getCacheControl());

        MockHttpServletRequest plain = new MockHttpServletRequest(
                "GET", "/api/v1/rag/documents");
        ResponseEntity.BodyBuilder untouched = ApiKeyRotationHttpPolicy.apply(
                ResponseEntity.ok(), plain);
        ResponseEntity<String> plainBuilt = untouched.body("payload");
        assertNull(plainBuilt.getHeaders().getCacheControl());
    }
}
