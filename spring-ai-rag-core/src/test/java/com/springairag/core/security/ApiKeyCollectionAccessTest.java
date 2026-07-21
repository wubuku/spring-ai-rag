package com.springairag.core.security;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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

    private RagApiKey restrictedKey(Long... collectionIds) {
        RagApiKey key = new RagApiKey();
        key.setRole(ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds(
                ApiKeyCollectionAccess.serializeAllowedIds(List.of(collectionIds)));
        return key;
    }
}
