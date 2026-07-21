package com.springairag.core.security;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.filter.ApiKeyAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Minimal Collection ACL for API keys.
 *
 * <p>Rules:
 * <ul>
 *   <li>No authenticated key entity (auth disabled / static legacy key) → unrestricted</li>
 *   <li>ADMIN role → unrestricted</li>
 *   <li>allowedCollectionIds null/blank → unrestricted</li>
 *   <li>Otherwise: request collectionIds must be a subset of allowed set;
 *       if request omits collectionIds, force allowed set</li>
 * </ul>
 */
public final class ApiKeyCollectionAccess {

    private ApiKeyCollectionAccess() {}

    public static RagApiKey currentKey() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return currentKey(attributes.getRequest());
        }
        return null;
    }

    public static RagApiKey currentKey(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object entity = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY);
        return entity instanceof RagApiKey k ? k : null;
    }

    public static List<Long> parseAllowedIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<Long> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                throw new IllegalStateException("Invalid empty collection ID in API key ACL");
            }
            try {
                long id = Long.parseLong(p);
                if (id <= 0) {
                    throw new IllegalStateException("Collection IDs in API key ACL must be positive");
                }
                out.add(id);
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "Invalid collection ID in API key ACL: " + p, e);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Non-blank API key ACL contains no valid collection IDs");
        }
        return List.copyOf(out);
    }

    public static String serializeAllowedIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        Set<Long> normalized = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw new IllegalArgumentException("Collection IDs must be positive");
            }
            normalized.add(id);
        }
        return normalized.stream()
                .sorted(Comparator.naturalOrder())
                .map(String::valueOf)
                .reduce((left, right) -> left + "," + right)
                .orElse(null);
    }

    public static boolean isUnrestricted(RagApiKey key) {
        if (key == null) {
            return true;
        }
        if (key.getRole() == ApiKeyRole.ADMIN) {
            return true;
        }
        return key.getAllowedCollectionIds() == null
                || key.getAllowedCollectionIds().isBlank();
    }

    public static Optional<Set<Long>> restrictedCollectionIds(RagApiKey key) {
        if (isUnrestricted(key)) {
            return Optional.empty();
        }
        return Optional.of(new LinkedHashSet<>(
                parseAllowedIds(key.getAllowedCollectionIds())));
    }

    /**
     * Resolve effective collection IDs for a retrieval request.
     *
     * @param requested client-supplied collection filter (may be null)
     * @param key       authenticated key (may be null)
     * @return effective collection IDs (null = no filter / all collections)
     * @throws SecurityException if requested IDs are outside the allow-list
     */
    public static List<Long> resolveCollectionIds(List<Long> requested, RagApiKey key) {
        if (isUnrestricted(key)) {
            return requested;
        }
        Set<Long> allowSet = restrictedCollectionIds(key).orElseThrow();
        List<Long> allowed = List.copyOf(allowSet);

        if (requested == null || requested.isEmpty()) {
            return allowed;
        }
        Set<Long> effective = new LinkedHashSet<>();
        for (Long id : requested) {
            if (id == null || id <= 0) {
                throw new SecurityException("Collection IDs must be positive");
            }
            if (!allowSet.contains(id)) {
                throw new SecurityException(
                        "API key is not allowed to access collectionId=" + id
                                + "; allowed=" + allowed);
            }
            effective.add(id);
        }
        return List.copyOf(effective);
    }

    public static void requireCollectionId(Long collectionId, RagApiKey key) {
        if (isUnrestricted(key)) {
            return;
        }
        if (collectionId == null
                || !restrictedCollectionIds(key).orElseThrow().contains(collectionId)) {
            throw new SecurityException(
                    "API key is not allowed to access collectionId=" + collectionId);
        }
    }

    public static Long resolveWritableCollectionId(
            Long requestedCollectionId, RagApiKey key) {
        if (isUnrestricted(key)) {
            return requestedCollectionId;
        }
        Set<Long> allowed = restrictedCollectionIds(key).orElseThrow();
        if (requestedCollectionId != null) {
            requireCollectionId(requestedCollectionId, key);
            return requestedCollectionId;
        }
        if (allowed.size() == 1) {
            return allowed.iterator().next();
        }
        throw new SecurityException(
                "A collectionId is required for API keys restricted to multiple collections");
    }

    public static void requireDocumentAccess(RagDocument document, RagApiKey key) {
        if (document == null || isUnrestricted(key)) {
            return;
        }
        requireCollectionId(document.getCollectionId(), key);
    }

    public static void requireCollectionCreationAllowed(RagApiKey key) {
        if (!isUnrestricted(key)) {
            throw new SecurityException(
                    "Restricted API keys cannot create, import, or clone collections");
        }
    }

    public static List<Long> resolveDelegatedAllowedIds(
            List<Long> requested, RagApiKey caller) {
        if (isUnrestricted(caller)) {
            return requested == null || requested.isEmpty()
                    ? null
                    : resolveCollectionIds(requested, caller);
        }
        if (requested == null || requested.isEmpty()) {
            return List.copyOf(restrictedCollectionIds(caller).orElseThrow());
        }
        return resolveCollectionIds(requested, caller);
    }
}
