package com.springairag.core.security;

import com.springairag.core.entity.ApiKeyRole;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 授权策略只读视图：遗留对象默认保留全量能力，显式能力按清单判断。 */
class ApiAccessPolicyTest {

    private static ApiAccessPolicy legacyPolicy() {
        return new ApiAccessPolicy() {
            @Override
            public String getPrincipalId() {
                return "rag_p_legacy";
            }

            @Override
            public String getCredentialId() {
                return "rag_k_legacy_v1";
            }

            @Override
            public ApiKeyRole getRole() {
                return ApiKeyRole.NORMAL;
            }

            @Override
            public String getAllowedCollectionIds() {
                return "kb-default,kb-extra";
            }

            @Override
            public LocalDateTime getExpiresAt() {
                return LocalDateTime.of(2027, 1, 1, 0, 0);
            }
        };
    }

    @Test
    void legacyPoliciesDefaultToNullMetadataAndFullCapabilities() {
        ApiAccessPolicy policy = legacyPolicy();

        assertNull(policy.getCredentialVersion());
        assertNull(policy.getPolicyVersion());
        assertNull(policy.getRequestsPerMinute());
        assertEquals(ApiCapabilitySupport.fullCapabilities(), policy.getCapabilities());
        assertTrue(policy.hasCapability("RAG_READ"));
        assertTrue(policy.hasCapability("RAG_WRITE"));
    }

    @Test
    void explicitCapabilityListsGateHasCapability() {
        ApiAccessPolicy readOnly = new ApiAccessPolicy() {
            @Override
            public String getPrincipalId() {
                return "rag_p_readonly";
            }

            @Override
            public String getCredentialId() {
                return "rag_k_readonly_v1";
            }

            @Override
            public ApiKeyRole getRole() {
                return ApiKeyRole.NORMAL;
            }

            @Override
            public String getAllowedCollectionIds() {
                return "kb-default";
            }

            @Override
            public LocalDateTime getExpiresAt() {
                return null;
            }

            @Override
            public List<String> getCapabilities() {
                return List.of("RAG_READ");
            }
        };

        assertTrue(readOnly.hasCapability("RAG_READ"));
        assertFalse(readOnly.hasCapability("RAG_WRITE"));
    }

    @Test
    void exposesCoreIdentityFields() {
        ApiAccessPolicy policy = legacyPolicy();

        assertEquals("rag_p_legacy", policy.getPrincipalId());
        assertEquals("rag_k_legacy_v1", policy.getCredentialId());
        assertEquals(ApiKeyRole.NORMAL, policy.getRole());
        assertEquals("kb-default,kb-extra", policy.getAllowedCollectionIds());
        assertEquals(LocalDateTime.of(2027, 1, 1, 0, 0), policy.getExpiresAt());
    }
}
