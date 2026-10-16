package com.springairag.core.service;

import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.enums.CollectionCoverageMode;
import com.springairag.api.validation.CollectionKeyValidator;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.exception.RagException;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.security.ApiKeyCollectionAccess;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 将外部 Collection 范围解析为授权后的内部检索范围。
 */
@Component
public class CollectionRetrievalScopeResolver {

    public static final int MAX_COLLECTIONS = 100;
    public static final int MAX_COVERAGE_COLLECTIONS = 20;
    public static final int MAX_DOCUMENT_IDS = 1000;

    private final CollectionIdentityResolver identityResolver;

    public CollectionRetrievalScopeResolver(
            CollectionIdentityResolver identityResolver) {
        this.identityResolver = identityResolver;
    }

    public RetrievalScope resolve(
            CollectionScopeMode requestedMode,
            List<Long> requestedIds,
            List<String> requestedKeys,
            List<Long> documentIds,
            String documentType,
            ApiAccessPolicy caller) {
        return resolve(
                requestedMode,
                CollectionCoverageMode.GLOBAL,
                requestedIds,
                requestedKeys,
                documentIds,
                documentType,
                caller);
    }

    public RetrievalScope resolve(
            CollectionScopeMode requestedMode,
            CollectionCoverageMode requestedCoverageMode,
            List<Long> requestedIds,
            List<String> requestedKeys,
            List<Long> documentIds,
            String documentType,
            ApiAccessPolicy caller) {
        boolean idsPresent = requestedIds != null;
        boolean keysPresent = requestedKeys != null;
        CollectionCoverageMode coverageMode = requestedCoverageMode == null
                ? CollectionCoverageMode.GLOBAL : requestedCoverageMode;
        List<Long> ids = validateIds(
                requestedIds, MAX_COLLECTIONS, "collectionIds", true);
        List<String> keys = validateKeys(requestedKeys);
        List<Long> documents = validateIds(
                documentIds, MAX_DOCUMENT_IDS, "documentIds", false);
        validateDocumentType(documentType);

        CollectionScopeMode mode = requestedMode;
        if (mode == null) {
            mode = idsPresent || keysPresent
                    ? CollectionScopeMode.SELECTED_COLLECTIONS
                    : CollectionScopeMode.CALLER_VISIBLE;
        }

        if (mode != CollectionScopeMode.SELECTED_COLLECTIONS
                && (idsPresent || keysPresent)) {
            throw new IllegalArgumentException(
                    mode + " must not include collectionIds or collectionKeys");
        }
        if (coverageMode == CollectionCoverageMode.EACH_COLLECTION
                && requestedMode != CollectionScopeMode.SELECTED_COLLECTIONS) {
            throw new IllegalArgumentException(
                    "EACH_COLLECTION coverage requires explicit SELECTED_COLLECTIONS scope");
        }
        if (coverageMode == CollectionCoverageMode.EACH_COLLECTION
                && mode != CollectionScopeMode.SELECTED_COLLECTIONS) {
            throw new IllegalArgumentException(
                    "EACH_COLLECTION coverage requires SELECTED_COLLECTIONS scope");
        }

        return switch (mode) {
            case CALLER_VISIBLE -> callerVisible(documents, documentType, caller);
            case ANY_COLLECTION -> anyCollection(documents, documentType, caller);
            case SELECTED_COLLECTIONS -> {
                if (!idsPresent && !keysPresent) {
                    throw new IllegalArgumentException(
                            "SELECTED_COLLECTIONS requires collectionIds or collectionKeys");
                }
                List<Long> resolved = resolveSelected(
                        idsPresent ? ids : null,
                        keysPresent ? keys : null,
                        caller);
                if (coverageMode == CollectionCoverageMode.EACH_COLLECTION
                        && resolved.size() > MAX_COVERAGE_COLLECTIONS) {
                    throw new IllegalArgumentException(
                            "EACH_COLLECTION coverage must not contain more than "
                                    + MAX_COVERAGE_COLLECTIONS + " Collections");
                }
                yield RetrievalScope.selectedCollections(
                        resolved, documents, documentType, coverageMode);
            }
        };
    }

    public void validateCoverageLimit(RetrievalScope scope, int limit) {
        if (scope == null
                || scope.coverageMode() != CollectionCoverageMode.EACH_COLLECTION) {
            return;
        }
        if (scope.collectionIds().size() > MAX_COVERAGE_COLLECTIONS) {
            throw new IllegalArgumentException(
                    "EACH_COLLECTION coverage must not contain more than "
                            + MAX_COVERAGE_COLLECTIONS + " Collections");
        }
        if (limit < scope.collectionIds().size() || limit > 100) {
            throw new IllegalArgumentException(
                    "EACH_COLLECTION coverage requires limit/maxResults between selected Collection count and 100");
        }
    }

    private RetrievalScope callerVisible(
            List<Long> documentIds, String documentType, ApiAccessPolicy caller) {
        if (ApiKeyCollectionAccess.isUnrestricted(caller)) {
            return RetrievalScope.unscoped(documentIds, documentType);
        }
        return RetrievalScope.selectedCollections(
                allowedIds(caller), documentIds, documentType);
    }

    private RetrievalScope anyCollection(
            List<Long> documentIds, String documentType, ApiAccessPolicy caller) {
        if (ApiKeyCollectionAccess.isUnrestricted(caller)) {
            return RetrievalScope.anyAssigned(documentIds, documentType);
        }
        return RetrievalScope.selectedCollections(
                allowedIds(caller), documentIds, documentType);
    }

    private List<Long> resolveSelected(
            List<Long> requestedIds,
            List<String> requestedKeys,
            ApiAccessPolicy caller) {
        List<Long> resolvedIds = requestedIds == null
                ? null
                : authorizeIds(requestedIds, caller);
        List<Long> resolvedKeys = requestedKeys == null
                ? null
                : resolveKeys(requestedKeys, caller);
        if (resolvedIds != null && resolvedKeys != null
                && !new LinkedHashSet<>(resolvedIds)
                .equals(new LinkedHashSet<>(resolvedKeys))) {
            throw new IllegalArgumentException(
                    "collectionIds and collectionKeys identify different collections");
        }
        return resolvedIds != null ? resolvedIds : resolvedKeys;
    }

    private List<Long> authorizeIds(List<Long> requestedIds, ApiAccessPolicy caller) {
        if (ApiKeyCollectionAccess.isUnrestricted(caller)) {
            return requestedIds.stream()
                    .map(id -> identityResolver.requireActive(id, null).getId())
                    .toList();
        }
        Set<Long> allowed = ApiKeyCollectionAccess.restrictedCollectionIds(caller)
                .orElseThrow();
        for (Long id : requestedIds) {
            if (!allowed.contains(id)) {
                throw new SecurityException("Collection is not authorized");
            }
            identityResolver.requireActive(id, null);
        }
        return requestedIds;
    }

    private List<Long> resolveKeys(List<String> keys, ApiAccessPolicy caller) {
        try {
            if (ApiKeyCollectionAccess.isUnrestricted(caller)) {
                return identityResolver.resolveActiveIds(null, keys);
            }
            return identityResolver.resolveActiveIdsWithinAllowed(
                    keys,
                    ApiKeyCollectionAccess.restrictedCollectionIds(caller)
                            .orElseThrow());
        } catch (RagException e) {
            if (!ApiKeyCollectionAccess.isUnrestricted(caller)
                    && e.getErrorCodeEnum()
                    != com.springairag.api.enums.ErrorCode.COLLECTION_ALREADY_RETIRED) {
                throw new SecurityException("Collection is not authorized");
            }
            throw e;
        }
    }

    private List<Long> allowedIds(ApiAccessPolicy caller) {
        return List.copyOf(ApiKeyCollectionAccess.restrictedCollectionIds(caller)
                .orElseThrow());
    }

    private List<Long> validateIds(
            List<Long> values, int maxSize, String field, boolean rejectEmpty) {
        if (values == null) {
            return List.of();
        }
        if (rejectEmpty && values.isEmpty()) {
            throw new IllegalArgumentException("Collection scope must not be empty");
        }
        if (values.size() > maxSize) {
            throw new IllegalArgumentException(
                    field + " must not contain more than " + maxSize + " items");
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long value : values) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(
                        field + " must contain positive IDs");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    private List<String> validateKeys(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Collection scope must not be empty");
        }
        if (values.size() > MAX_COLLECTIONS) {
            throw new IllegalArgumentException(
                    "collectionKeys must not contain more than "
                            + MAX_COLLECTIONS + " items");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (!CollectionKeyValidator.isValid(value)) {
                throw new IllegalArgumentException(
                        "collectionKey must contain 1-128 visible ASCII characters");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    private void validateDocumentType(String documentType) {
        if (documentType != null && documentType.isBlank()) {
            throw new IllegalArgumentException("documentType must not be blank");
        }
    }
}
