package com.springairag.core.security;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.exception.RagException;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiKeyCollectionAccess 委托解析长尾（Batch 541，JaCoCo 驱动）：
 * resolveDelegatedAllowedKeys 的 null/空/受限失败包装、resolve
 * WritableCollectionId 的 allow-list 单值推断与多值拒绝、parse
 * AllowedIds/serializeAllowedIds 的往返与非法输入拒绝。
 */
class ApiKeyCollectionAccessDelegatedTailTest {

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
    void delegatedKeysNullRequestReturnsNull() {
        assertNull(ApiKeyCollectionAccess.resolveDelegatedAllowedKeys(
                null, null, resolver));
    }

    @Test
    void delegatedKeysEmptyRequestIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyCollectionAccess.resolveDelegatedAllowedKeys(
                        List.of(), null, resolver));
    }

    @Test
    void delegatedKeysUnrestrictedResolvesThroughIdentityResolver() {
        when(resolver.resolveActiveIds(isNull(), eq(List.of("kb-10"))))
                .thenReturn(List.of(10L));

        List<Long> resolved = ApiKeyCollectionAccess
                .resolveDelegatedAllowedKeys(
                        List.of("kb-10"), null, resolver);

        assertEquals(List.of(10L), resolved);
    }

    @Test
    void delegatedKeysRestrictedFailureWrapsAsSecurityException() {
        when(resolver.resolveActiveIdsWithinAllowed(
                eq(List.of("kb-404")),
                eq(Set.of(10L))))
                .thenThrow(new RagException(
                        ErrorCode.NOT_FOUND, "no such collection"));

        assertThrows(SecurityException.class,
                () -> ApiKeyCollectionAccess.resolveDelegatedAllowedKeys(
                        List.of("kb-404"), restricted("10"), resolver));
    }

    @Test
    void writableCollectionIdDefaultsToSoleAllowedCollection() {
        Long resolved = ApiKeyCollectionAccess.resolveWritableCollectionId(
                null, restricted("10"));

        assertEquals(10L, resolved);
    }

    @Test
    void writableCollectionIdWithMultipleAllowedRequiresExplicitId() {
        assertThrows(SecurityException.class,
                () -> ApiKeyCollectionAccess.resolveWritableCollectionId(
                        null, restricted("10,11")));
    }

    @Test
    void parseAllowedIdsRejectsEmptyPartNonPositiveAndGarbage() {
        assertThrows(IllegalStateException.class,
                () -> ApiKeyCollectionAccess.parseAllowedIds("1,,2"));
        assertThrows(IllegalStateException.class,
                () -> ApiKeyCollectionAccess.parseAllowedIds("1,-3"));
        assertThrows(IllegalStateException.class,
                () -> ApiKeyCollectionAccess.parseAllowedIds("1,abc"));
        assertEquals(List.of(1L, 2L),
                ApiKeyCollectionAccess.parseAllowedIds("1, 2, 1"));
    }

    @Test
    void serializeAllowedIdsDeduplicatesAndDropsEmptyInput() {
        assertNull(ApiKeyCollectionAccess.serializeAllowedIds(null));
        assertNull(ApiKeyCollectionAccess.serializeAllowedIds(List.of()));
        assertEquals("10,11",
                ApiKeyCollectionAccess.serializeAllowedIds(
                        List.of(10L, 11L, 10L)));
    }

    @Test
    void deprecatedCurrentKeyDelegatesToCurrentPolicy() {
        assertSame(ApiKeyCollectionAccess.currentPolicy(),
                ApiKeyCollectionAccess.currentKey());
    }
}
