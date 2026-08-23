package com.springairag.core.security;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.service.CollectionIdentityResolver;
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

    public static ApiAccessPolicy currentPolicy() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return currentPolicy(attributes.getRequest());
        }
        return null;
    }

    public static ApiAccessPolicy currentPolicy(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object policy = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE);
        if (policy instanceof ApiAccessPolicy accessPolicy) {
            return accessPolicy;
        }
        Object legacy = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY);
        return legacy instanceof ApiAccessPolicy accessPolicy ? accessPolicy : null;
    }

    /** @deprecated 使用不可变 {@link #currentPolicy()}。 */
    @Deprecated
    public static ApiAccessPolicy currentKey() { return currentPolicy(); }

    /** @deprecated 使用不可变 {@link #currentPolicy(HttpServletRequest)}。 */
    @Deprecated
    public static ApiAccessPolicy currentKey(HttpServletRequest request) {
        return currentPolicy(request);
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

    public static boolean isUnrestricted(ApiAccessPolicy key) {
        if (key == null) {
            return true;
        }
        if (key.getRole() == ApiKeyRole.ADMIN) {
            return true;
        }
        return key.getAllowedCollectionIds() == null
                || key.getAllowedCollectionIds().isBlank();
    }

    public static Optional<Set<Long>> restrictedCollectionIds(ApiAccessPolicy key) {
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
    public static List<Long> resolveCollectionIds(List<Long> requested, ApiAccessPolicy key) {
        if (isUnrestricted(key)) {
            if (requested != null && requested.isEmpty()) {
                throw new IllegalArgumentException("Collection scope must not be empty");
            }
            return requested;
        }
        Set<Long> allowSet = restrictedCollectionIds(key).orElseThrow();
        List<Long> allowed = List.copyOf(allowSet);

        if (requested == null) {
            return allowed;
        }
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("Collection scope must not be empty");
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

    /**
     * Resolve preferred external keys plus the legacy numeric IDs.
     *
     * <p>Unknown keys are deliberately converted to FORBIDDEN for restricted
     * callers, so an API key cannot probe whether an inaccessible Collection
     * exists. Unrestricted callers retain the normal 404 from the resolver.
     */
    public static List<Long> resolveCollectionIds(
            List<Long> requestedIds,
            List<String> requestedKeys,
            ApiAccessPolicy key,
            CollectionIdentityResolver resolver) {
        if (requestedKeys == null) {
            return resolveCollectionIds(requestedIds, key);
        }
        if (requestedKeys.isEmpty()) {
            throw new IllegalArgumentException("Collection scope must not be empty");
        }
        if (requestedIds != null && requestedIds.isEmpty()) {
            throw new IllegalArgumentException("Collection scope must not be empty");
        }
        try {
            List<Long> resolvedKeys = isUnrestricted(key)
                    ? resolver.resolveActiveIds(null, requestedKeys)
                    : resolver.resolveActiveIdsWithinAllowed(
                            requestedKeys,
                            restrictedCollectionIds(key).orElseThrow());
            if (requestedIds != null) {
                List<Long> resolvedIds = resolveCollectionIds(requestedIds, key);
                if (!new LinkedHashSet<>(resolvedIds)
                        .equals(new LinkedHashSet<>(resolvedKeys))) {
                    throw new IllegalArgumentException(
                            "collectionIds and collectionKeys identify different collections");
                }
                return resolvedIds;
            }
            return resolvedKeys;
        } catch (com.springairag.core.exception.RagException e) {
            if (!isUnrestricted(key)) {
                throw new SecurityException("Collection is not authorized");
            }
            throw e;
        }
    }

    public static List<Long> resolveDelegatedAllowedKeys(
            List<String> requestedKeys,
            ApiAccessPolicy caller,
            CollectionIdentityResolver resolver) {
        if (requestedKeys == null) {
            return null;
        }
        if (requestedKeys.isEmpty()) {
            throw new IllegalArgumentException("Allowed collection keys must not be empty");
        }
        try {
            return resolveDelegatedAllowedIds(
                    resolveCollectionIds(null, requestedKeys, caller, resolver), caller);
        } catch (com.springairag.core.exception.RagException e) {
            if (!isUnrestricted(caller)) {
                throw new SecurityException("Collection is not authorized");
            }
            throw e;
        }
    }

    /**
     * Resolve an active Collection by key without exposing global key existence
     * to a caller restricted to an internal ID allow-list.
     */
    public static RagCollection requireActiveCollectionByKey(
            String requestedKey,
            ApiAccessPolicy caller,
            CollectionIdentityResolver resolver) {
        if (isUnrestricted(caller)) {
            return resolver.requireActive(null, requestedKey);
        }
        try {
            return resolver.requireActiveWithinAllowed(
                    requestedKey,
                    restrictedCollectionIds(caller).orElseThrow());
        } catch (com.springairag.core.exception.RagException e) {
            throw new SecurityException("Collection is not authorized");
        }
    }

    /**
     * Restore uses the same anti-enumeration rule but includes soft-deleted rows.
     */
    public static RagCollection requireIncludingDeletedCollectionByKey(
            String requestedKey,
            ApiAccessPolicy caller,
            CollectionIdentityResolver resolver) {
        if (isUnrestricted(caller)) {
            return resolver.requireIncludingDeleted(null, requestedKey);
        }
        try {
            return resolver.requireIncludingDeletedWithinAllowed(
                    requestedKey,
                    restrictedCollectionIds(caller).orElseThrow());
        } catch (com.springairag.core.exception.RagException e) {
            throw new SecurityException("Collection is not authorized");
        }
    }

    public static void requireCollectionId(Long collectionId, ApiAccessPolicy key) {
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
            Long requestedCollectionId, ApiAccessPolicy key) {
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

    public static void requireDocumentAccess(RagDocument document, ApiAccessPolicy key) {
        if (document == null || isUnrestricted(key)) {
            return;
        }
        requireCollectionId(document.getCollectionId(), key);
    }

    public static void requireCollectionCreationAllowed(ApiAccessPolicy key) {
        if (!isUnrestricted(key)) {
            throw new SecurityException(
                    "Restricted API keys cannot create, import, or clone collections");
        }
    }

    public static List<Long> resolveDelegatedAllowedIds(
            List<Long> requested, ApiAccessPolicy caller) {
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
