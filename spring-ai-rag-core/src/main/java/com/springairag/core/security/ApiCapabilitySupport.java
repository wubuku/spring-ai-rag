package com.springairag.core.security;

import com.springairag.core.entity.ApiKeyRole;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * API principal capability constants and canonicalization rules.
 */
public final class ApiCapabilitySupport {

    public static final String RAG_READ = "RAG_READ";
    public static final String RAG_WRITE = "RAG_WRITE";
    public static final String FULL_SERIALIZED = RAG_READ + "," + RAG_WRITE;

    private static final List<String> READ_ONLY = List.of(RAG_READ);
    private static final List<String> FULL = List.of(RAG_READ, RAG_WRITE);

    private ApiCapabilitySupport() {
    }

    public static List<String> fullCapabilities() {
        return FULL;
    }

    public static List<String> normalizeRequested(List<String> requested) {
        if (requested == null) {
            return FULL;
        }
        if (requested.isEmpty()) {
            throw new IllegalArgumentException(
                    "capabilities must contain RAG_READ or RAG_READ and RAG_WRITE");
        }

        Set<String> unique = new LinkedHashSet<>();
        for (String capability : requested) {
            if (!RAG_READ.equals(capability) && !RAG_WRITE.equals(capability)) {
                throw new IllegalArgumentException(
                        "Unsupported API capability: " + capability);
            }
            if (!unique.add(capability)) {
                throw new IllegalArgumentException(
                        "Duplicate API capability: " + capability);
            }
        }
        if (!unique.contains(RAG_READ)) {
            throw new IllegalArgumentException("RAG_WRITE requires RAG_READ");
        }
        return unique.contains(RAG_WRITE) ? FULL : READ_ONLY;
    }

    /**
     * Parse the database representation. Null is only accepted for V48
     * compatibility; every other non-canonical value fails closed.
     */
    public static List<String> normalizePersisted(String persisted) {
        if (persisted == null) {
            return FULL;
        }
        if (RAG_READ.equals(persisted)) {
            return READ_ONLY;
        }
        if (FULL_SERIALIZED.equals(persisted)) {
            return FULL;
        }
        throw new InvalidPersistedCapabilitiesException(persisted);
    }

    public static String serialize(List<String> capabilities) {
        List<String> normalized = normalizeRequested(capabilities);
        return normalized.size() == 1 ? RAG_READ : FULL_SERIALIZED;
    }

    public static List<String> effectiveForRole(ApiKeyRole role,
                                                List<String> capabilities) {
        return role == ApiKeyRole.ADMIN ? FULL : capabilities;
    }

    public static boolean hasRead(ApiAccessPolicy policy) {
        return policy != null && policy.getCapabilities().contains(RAG_READ);
    }

    public static boolean hasWrite(ApiAccessPolicy policy) {
        return policy != null && policy.getCapabilities().contains(RAG_WRITE);
    }

    public static final class InvalidPersistedCapabilitiesException
            extends IllegalStateException {
        public InvalidPersistedCapabilitiesException(String persisted) {
            super("Invalid persisted API capabilities: " + persisted);
        }
    }
}
