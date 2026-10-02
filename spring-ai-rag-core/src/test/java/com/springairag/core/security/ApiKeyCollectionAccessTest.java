package com.springairag.core.security;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyCollectionAccessTest {

    @Test
    void serializeAndParse_normalizesPositiveUniqueIds() {
        String stored = ApiKeyCollectionAccess.serializeAllowedIds(
                List.of(3L, 1L, 3L, 2L));

        assertEquals("1,2,3", stored);
        assertEquals(List.of(1L, 2L, 3L),
                ApiKeyCollectionAccess.parseAllowedIds(stored));
    }

    @Test
    void parseMalformedAcl_failsClosed() {
        assertThrows(IllegalStateException.class,
                () -> ApiKeyCollectionAccess.parseAllowedIds("1,invalid,2"));
        assertThrows(IllegalStateException.class,
                () -> ApiKeyCollectionAccess.parseAllowedIds("1,,2"));
        assertThrows(IllegalStateException.class,
                () -> ApiKeyCollectionAccess.parseAllowedIds("0"));
    }

    @Test
    void resolveCollectionIds_omittedRequestUsesAllowedSet() {
        RagApiKey key = restrictedKey(3L, 7L);

        assertEquals(List.of(3L, 7L),
                ApiKeyCollectionAccess.resolveCollectionIds(null, key));
    }

    @Test
    void resolveCollectionIds_rejectsIdsOutsideAllowedSet() {
        RagApiKey key = restrictedKey(3L, 7L);

        SecurityException error = assertThrows(SecurityException.class,
                () -> ApiKeyCollectionAccess.resolveCollectionIds(
                        List.of(3L, 9L), key));
        assertTrue(error.getMessage().contains("collectionId=9"));
    }

    @Test
    void adminKeyIsAlwaysUnrestricted() {
        RagApiKey key = restrictedKey(3L);
        key.setRole(ApiKeyRole.ADMIN);

        assertTrue(ApiKeyCollectionAccess.isUnrestricted(key));
        assertEquals(List.of(99L),
                ApiKeyCollectionAccess.resolveCollectionIds(List.of(99L), key));
    }

    /**
     * Batch 816。没有策略就是"不受限"，而这是一条**刻意保留的 fail-open 默认值**，
     * 不是疏漏：auth 关闭的本地部署根本没有 API key 策略，此时"不受限"才是对的。
     *
     * <p>把它钉在这里，是因为它的危险完全体现在下游——任何把请求上下文丢成
     * {@code null} 的调用方都会静默拿到全作用域，而不是报错。
     * {@code verify-null-request-forwarding.mjs} 负责挡住那类调用方；
     * 这条用例负责让下一个改这里的人知道"这是有意的，改它要连带想清楚本地部署"。
     */
    @Test
    void absentPolicyMeansUnrestrictedByDesign() {
        assertTrue(ApiKeyCollectionAccess.isUnrestricted(null),
                "无策略必须解释为不受限（auth 关闭的本地部署）；"
                        + "改成 false 会让本地部署检索不到任何东西");
        assertEquals(Optional.empty(),
                ApiKeyCollectionAccess.restrictedCollectionIds(null));
    }

    @Test
    void delegatedAclCannotExceedRestrictedCaller() {
        RagApiKey caller = restrictedKey(3L, 7L);

        assertEquals(List.of(3L, 7L),
                ApiKeyCollectionAccess.resolveDelegatedAllowedIds(null, caller));
        assertEquals(List.of(7L),
                ApiKeyCollectionAccess.resolveDelegatedAllowedIds(
                        List.of(7L), caller));
        assertThrows(SecurityException.class,
                () -> ApiKeyCollectionAccess.resolveDelegatedAllowedIds(
                        List.of(9L), caller));
    }

    @Test
    void documentWithoutCollectionIsDeniedForRestrictedKey() {
        RagDocument document = new RagDocument();
        document.setId(42L);

        assertThrows(SecurityException.class,
                () -> ApiKeyCollectionAccess.requireDocumentAccess(
                        document, restrictedKey(3L)));
    }

    @Test
    void restrictedCollectionIdsReturnsConfiguredSet() {
        assertEquals(Set.of(3L, 7L),
                ApiKeyCollectionAccess.restrictedCollectionIds(
                        restrictedKey(3L, 7L)).orElseThrow());
    }

    @Test
    void explicitEmptyScopeIsRejectedForRestrictedAndUnrestrictedKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyCollectionAccess.resolveCollectionIds(List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyCollectionAccess.resolveCollectionIds(
                        List.of(), restrictedKey(3L)));

        CollectionIdentityResolver resolver = mock(CollectionIdentityResolver.class);
        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyCollectionAccess.resolveCollectionIds(
                        null, List.of(), null, resolver));
    }

    @Test
    void keyAndIdScopesCompareAsSetsRegardlessOfOrder() {
        CollectionIdentityResolver resolver = mock(CollectionIdentityResolver.class);
        when(resolver.resolveActiveIds(null, List.of("two", "one")))
                .thenReturn(List.of(2L, 1L));

        assertEquals(List.of(1L, 2L),
                ApiKeyCollectionAccess.resolveCollectionIds(
                        List.of(1L, 2L), List.of("two", "one"), null, resolver));
    }

    @Test
    void keyAndIdScopeMismatchIsBadRequest() {
        CollectionIdentityResolver resolver = mock(CollectionIdentityResolver.class);
        when(resolver.resolveActiveIds(null, List.of("two")))
                .thenReturn(List.of(2L));

        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyCollectionAccess.resolveCollectionIds(
                        List.of(1L), List.of("two"), null, resolver));
    }

    @Test
    void unknownKeyIs404ForUnrestrictedAnd403ForRestrictedCaller() {
        CollectionIdentityResolver resolver = mock(CollectionIdentityResolver.class);
        RagException missing = new RagException(
                ErrorCode.COLLECTION_NOT_FOUND, "missing");
        when(resolver.resolveActiveIds(null, List.of("missing")))
                .thenThrow(missing);

        assertSame(missing, assertThrows(RagException.class,
                () -> ApiKeyCollectionAccess.resolveCollectionIds(
                        null, List.of("missing"), null, resolver)));

        RagApiKey restricted = restrictedKey(3L);
        when(resolver.resolveActiveIdsWithinAllowed(
                List.of("missing"), Set.of(3L))).thenThrow(missing);
        assertThrows(SecurityException.class,
                () -> ApiKeyCollectionAccess.resolveCollectionIds(
                        null, List.of("missing"), restricted, resolver));
    }

    @Test
    void singleKeyLookupPreserves404ForUnrestrictedAndHidesRestrictedExistence() {
        CollectionIdentityResolver resolver = mock(CollectionIdentityResolver.class);
        RagException missing = new RagException(
                ErrorCode.COLLECTION_NOT_FOUND, "missing");
        when(resolver.requireActive(null, "missing")).thenThrow(missing);

        assertSame(missing, assertThrows(RagException.class,
                () -> ApiKeyCollectionAccess.requireActiveCollectionByKey(
                        "missing", null, resolver)));

        RagApiKey restricted = restrictedKey(3L);
        when(resolver.requireActiveWithinAllowed("missing", Set.of(3L)))
                .thenThrow(missing);
        when(resolver.requireIncludingDeletedWithinAllowed("missing", Set.of(3L)))
                .thenThrow(missing);

        assertThrows(SecurityException.class,
                () -> ApiKeyCollectionAccess.requireActiveCollectionByKey(
                        "missing", restricted, resolver));
        assertThrows(SecurityException.class,
                () -> ApiKeyCollectionAccess.requireIncludingDeletedCollectionByKey(
                        "missing", restricted, resolver));
    }

    @Test
    void singleKeyLookupReturnsAuthorizedCollection() {
        CollectionIdentityResolver resolver = mock(CollectionIdentityResolver.class);
        RagApiKey restricted = restrictedKey(3L);
        RagCollection collection = new RagCollection();
        collection.setId(3L);
        collection.setCollectionKey("allowed");
        when(resolver.requireActiveWithinAllowed("allowed", Set.of(3L)))
                .thenReturn(collection);

        assertSame(collection,
                ApiKeyCollectionAccess.requireActiveCollectionByKey(
                        "allowed", restricted, resolver));
    }

    private RagApiKey restrictedKey(Long... collectionIds) {
        RagApiKey key = new RagApiKey();
        key.setRole(ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds(
                ApiKeyCollectionAccess.serializeAllowedIds(List.of(collectionIds)));
        return key;
    }
}
