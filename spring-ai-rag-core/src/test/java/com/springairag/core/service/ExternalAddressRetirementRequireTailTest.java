package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.security.ApiAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ExternalAddressRetirementService 永久阻断长尾（Batch 565，JaCoCo
 * 驱动）：无退役标记放行、有标记时拒绝（unrestricted 附带当前目标
 * 键、受限调用方不泄露目标键）。
 */
class ExternalAddressRetirementRequireTailTest {

    private JdbcTemplate jdbcTemplate;
    private ExternalAddressRetirementService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new ExternalAddressRetirementService(jdbcTemplate);
    }

    private void stubMarkers(List<Map<String, Object>> rows) {
        when(jdbcTemplate.queryForList(
                anyString(), any(Object[].class))).thenReturn(rows);
    }

    @Test
    void noMarkerAllowsAccess() {
        stubMarkers(List.of());

        assertDoesNotThrow(() -> service.requireNotRetired(
                5L, "crm", "cms:1"));
    }

    @Test
    void markerRejectsWithTargetKeyForUnrestrictedCaller() {
        stubMarkers(List.of(Map.of(
                "target_collection_id", 20L,
                "collection_key", "kb-new:v1")));

        RagException error = assertThrows(RagException.class,
                () -> service.requireNotRetired(5L, "crm", "cms:1"));

        assertEquals(ErrorCode.EXTERNAL_IDENTITY_RELOCATED,
                error.getErrorCodeEnum());
        assertEquals("The external identity was relocated and this address"
                        + " is retired; current targetCollectionKey=kb-new:v1",
                error.getMessage());
    }

    @Test
    void markerRejectsWithoutLeakingTargetForRestrictedCaller() {
        stubMarkers(List.of(Map.of(
                "target_collection_id", 20L,
                "collection_key", "kb-new:v1")));
        // 注入受限 API key 请求属性：目标集合不在 allow-list 内。
        MockKeyHolder.set("10");

        try {
            RagException error = assertThrows(RagException.class,
                    () -> service.requireNotRetired(5L, "crm", "cms:1"));

            assertEquals(ErrorCode.EXTERNAL_IDENTITY_RELOCATED,
                    error.getErrorCodeEnum());
            assertEquals("The external identity was relocated and this"
                            + " address is retired",
                    error.getMessage());
        } finally {
            MockKeyHolder.clear();
        }
    }

    private static final class MockKeyHolder {
        private static void set(String allowedIds) {
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            var policy = (ApiAccessPolicy)
                    java.lang.reflect.Proxy.newProxyInstance(
                            ApiAccessPolicy.class.getClassLoader(),
                            new java.lang.Class<?>[] {
                                    ApiAccessPolicy.class},
                            (proxy, method, args) -> switch (method.getName()) {
                                case "getPrincipalId" -> "rag_p_restricted";
                                case "getCredentialId" -> "rag_k_restricted";
                                case "getRole" -> com.springairag.core.entity
                                        .ApiKeyRole.NORMAL;
                                case "getAllowedCollectionIds" -> allowedIds;
                                case "getExpiresAt" -> null;
                                default -> null;
                            });
            request.setAttribute(
                    com.springairag.core.filter.ApiKeyAuthFilter
                            .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                    policy);
            org.springframework.web.context.request.RequestContextHolder
                    .setRequestAttributes(
                            new org.springframework.web.context.request
                                    .ServletRequestAttributes(request));
        }

        private static void clear() {
            org.springframework.web.context.request.RequestContextHolder
                    .resetRequestAttributes();
        }
    }

}
