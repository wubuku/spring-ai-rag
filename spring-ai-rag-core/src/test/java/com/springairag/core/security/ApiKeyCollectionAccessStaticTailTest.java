package com.springairag.core.security;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;

import java.time.LocalDateTime;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiKeyCollectionAccess 静态守卫长尾（Batch 653，JaCoCo 驱动）：
 * 已废弃 currentKey 委托、parseAllowedIds 全空白 token 拒绝、
 * serializeAllowedIds 非正数拒绝、受限键 resolveCollectionIds 的
 * 非正 ID 拒绝、带 keys 时空 IDs 拒绝、无限制调用方委托失败的
 * RagException 重抛、受限键显式集合 ID 的可达放行。
 */
class ApiKeyCollectionAccessStaticTailTest {

    private CollectionIdentityResolver resolver =
            mock(CollectionIdentityResolver.class);

    private static ApiAccessPolicy restricted(String allowedIds) {
        return new ApiAccessPolicy() {
            @Override public String getPrincipalId() { return "rag_p_1"; }
            @Override public String getCredentialId() { return "rag_k_1"; }
            @Override public ApiKeyRole getRole() { return ApiKeyRole.NORMAL; }
            @Override public String getAllowedCollectionIds() { return allowedIds; }
            @Override public LocalDateTime getExpiresAt() { return null; }
        };
    }

    @Test
    void deprecatedCurrentKeyDelegatesToCurrentPolicy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ApiAccessPolicy policy = restricted("10");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                policy);

        assertSame(policy, ApiKeyCollectionAccess.currentKey(request));
        assertNotNull(ApiKeyCollectionAccess.currentPolicy(request));
    }

    @Test
    void parseAllowedIdsWithOnlyBlankTokensIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> ApiKeyCollectionAccess.parseAllowedIds(" , , "));
    }

    @Test
    void serializeAllowedIdsRejectsNonPositiveIds() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyCollectionAccess.serializeAllowedIds(
                        List.of(10L, 0L)));
    }

    @Test
    void restrictedResolveRejectsNonPositiveRequestedIds() {
        ApiAccessPolicy policy = restricted("10");

        assertThrows(SecurityException.class,
                () -> ApiKeyCollectionAccess.resolveCollectionIds(
                        List.of(10L, 0L), policy));
    }

    @Test
    void resolveWithKeysRejectsEmptyRequestedIds() {
        when(resolver.resolveActiveIds(isNull(), eq(List.of("kb-10"))))
                .thenReturn(List.of(10L));

        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyCollectionAccess.resolveCollectionIds(
                        List.of(), List.of("kb-10"), null, resolver));
    }

    @Test
    void delegatedKeysUnrestrictedFailureRethrowsRagException() {
        when(resolver.resolveActiveIds(isNull(), eq(List.of("kb-10"))))
                .thenThrow(new RagException(
                        ErrorCode.NOT_FOUND, "no such collection"));

        RagException error = assertThrows(RagException.class,
                () -> ApiKeyCollectionAccess.resolveDelegatedAllowedKeys(
                        List.of("kb-10"), null, resolver));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void writableCollectionIdWithAllowedExplicitIdPasses() {
        assertEquals(10L, ApiKeyCollectionAccess.resolveWritableCollectionId(
                10L, restricted("10")));
    }

    @Test
    void nullRequestYieldsNullPolicy() {
        assertNull(ApiKeyCollectionAccess.currentPolicy(null));
    }
}
