package com.springairag.core.security;

import com.springairag.core.entity.ApiKeyRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiCapabilitySupportTest {

    @Test
    void normalizesSupportedSetsInStableOrder() {
        assertEquals(List.of("RAG_READ"),
                ApiCapabilitySupport.normalizeRequested(List.of("RAG_READ")));
        assertEquals(List.of("RAG_READ", "RAG_WRITE"),
                ApiCapabilitySupport.normalizeRequested(
                        List.of("RAG_WRITE", "RAG_READ")));
    }

    @Test
    void omittedRequestDefaultsToFullAndNullPersistedValueIsV48Compatible() {
        assertEquals(ApiCapabilitySupport.fullCapabilities(),
                ApiCapabilitySupport.normalizeRequested(null));
        assertEquals(ApiCapabilitySupport.fullCapabilities(),
                ApiCapabilitySupport.normalizePersisted(null));
    }

    @Test
    void rejectsEmptyDuplicateUnknownAndWriteOnlySets() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ApiCapabilitySupport.normalizeRequested(List.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ApiCapabilitySupport.normalizeRequested(
                                List.of("RAG_READ", "RAG_READ"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ApiCapabilitySupport.normalizeRequested(
                                List.of("RAG_UNKNOWN"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ApiCapabilitySupport.normalizeRequested(
                                List.of("RAG_WRITE"))));
    }

    @Test
    void rejectsNonCanonicalPersistedValueFailClosed() {
        assertThrows(
                ApiCapabilitySupport.InvalidPersistedCapabilitiesException.class,
                () -> ApiCapabilitySupport.normalizePersisted("RAG_WRITE"));
        assertThrows(
                ApiCapabilitySupport.InvalidPersistedCapabilitiesException.class,
                () -> ApiCapabilitySupport.normalizePersisted(""));
    }

    @Test
    void adminEffectiveCapabilityIsAlwaysFull() {
        assertEquals(ApiCapabilitySupport.fullCapabilities(),
                ApiCapabilitySupport.effectiveForRole(
                        ApiKeyRole.ADMIN, List.of("RAG_READ")));
        assertEquals(List.of("RAG_READ"),
                ApiCapabilitySupport.effectiveForRole(
                        ApiKeyRole.NORMAL, List.of("RAG_READ")));
    }
}
