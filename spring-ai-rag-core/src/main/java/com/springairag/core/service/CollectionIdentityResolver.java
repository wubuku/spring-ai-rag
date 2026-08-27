package com.springairag.core.service;

import com.springairag.api.enums.ErrorCode;
import com.springairag.api.validation.CollectionKeyValidator;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.observability.IntegrationObservationContext;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * Converts external Collection identities into the internal numeric ID.
 *
 * <p>The resolver is deliberately the only place where an endpoint needs to
 * understand the relationship between {@code collectionId} and
 * {@code collectionKey}. It never normalizes a key and fails when two supplied
 * identities disagree.
 */
@Component
public class CollectionIdentityResolver {

    private static final Logger log =
            LoggerFactory.getLogger(CollectionIdentityResolver.class);

    private final RagCollectionRepository repository;

    public CollectionIdentityResolver(RagCollectionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Optional<RagCollection> findActive(Long id, String key) {
        validatePair(id, key);
        if (key != null) {
            return repository.findByCollectionKeyAndDeletedFalse(key)
                    .map(collection -> requireMatchingId(id, collection));
        }
        return repository.findByIdAndDeletedFalse(id);
    }

    public Optional<RagCollection> findIncludingDeleted(Long id, String key) {
        validatePair(id, key);
        if (key != null) {
            return repository.findByCollectionKey(key)
                    .map(collection -> requireMatchingId(id, collection));
        }
        return repository.findById(id);
    }

    public RagCollection requireActive(Long id, String key) {
        RagCollection collection = requireActiveUnobserved(id, key);
        IntegrationObservationContext.addAuthorizedCollection(collection.getId());
        return collection;
    }

    public RagCollection requireIncludingDeleted(Long id, String key) {
        return findIncludingDeleted(id, key)
                .orElseThrow(() -> new RagException(
                        ErrorCode.COLLECTION_NOT_FOUND,
                        key != null
                                ? "Collection not found: collectionKey=" + key
                                : "Collection not found: id=" + id));
    }

    /**
     * Reserve an active Collection write position before dependent rows are written.
     *
     * <p>The conditional update is the first write in the caller's transaction.
     * Its row lock is held until commit or rollback, so purge and every protected
     * mutation share the same Collection-first lock order.
     */
    public ActiveCollectionToken beginActiveWrite(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("collectionId must be positive");
        }
        RagCollection collection = requireActive(id, null);
        long version = collection.getVersion() == null ? 0L : collection.getVersion();
        if (!Boolean.TRUE.equals(collection.getEnabled())
                || repository.advanceActiveVersion(id, version) != 1) {
            throw new ObjectOptimisticLockingFailureException(
                    RagCollection.class, id);
        }
        return new ActiveCollectionToken(id, version + 1);
    }

    /**
     * Reserve multiple Collections in a stable order.
     */
    public List<ActiveCollectionToken> beginActiveWrites(
            Collection<Long> collectionIds) {
        if (collectionIds == null || collectionIds.isEmpty()) {
            return List.of();
        }
        List<Long> ordered = new ArrayList<>(new LinkedHashSet<>(collectionIds));
        ordered.removeIf(Objects::isNull);
        ordered.sort(Comparator.naturalOrder());
        List<ActiveCollectionToken> tokens = new ArrayList<>(ordered.size());
        for (Long id : ordered) {
            tokens.add(beginActiveWrite(id));
        }
        return List.copyOf(tokens);
    }

    /**
     * Compatibility no-op. The token is consumed by {@link #beginActiveWrite(Long)}
     * before dependent writes.
     */
    public void confirmActiveWrite(ActiveCollectionToken token) {
        // Kept so existing callers can migrate without a flag day.
    }

    /**
     * Compatibility name for callers that only need an active-collection
     * snapshot. This method performs no database lock.
     */
    public RagCollection requireActiveForShare(Long id) {
        return requireActive(id, null);
    }

    /**
     * Resolve one active key only among IDs the caller is already authorized to use.
     */
    public RagCollection requireActiveWithinAllowed(
            String key, Collection<Long> allowedIds) {
        Optional<RagCollection> active = findWithinAllowed(
                key, allowedIds, false);
        if (active.isEmpty()) {
            Optional<RagCollection> existing = findWithinAllowed(
                    key, allowedIds, true);
            if (existing.filter(collection -> collection.getPurgedAt() != null)
                    .isPresent()) {
                throw new RagException(
                        ErrorCode.COLLECTION_ALREADY_RETIRED,
                        "Collection is permanently retired");
            }
        }
        RagCollection collection = active.orElseThrow(() -> new RagException(
                ErrorCode.COLLECTION_NOT_FOUND,
                "Collection not found: collectionKey=" + key));
        IntegrationObservationContext.addAuthorizedCollection(collection.getId());
        return collection;
    }

    /**
     * Resolve one key, including soft-deleted rows, only among authorized IDs.
     */
    public RagCollection requireIncludingDeletedWithinAllowed(
            String key, Collection<Long> allowedIds) {
        return findWithinAllowed(key, allowedIds, true)
                .orElseThrow(() -> new RagException(
                        ErrorCode.COLLECTION_NOT_FOUND,
                        "Collection not found: collectionKey=" + key));
    }

    public Long resolveActiveId(Long id, String key) {
        return requireActive(id, key).getId();
    }

    public Long resolveIncludingDeletedId(Long id, String key) {
        return requireIncludingDeleted(id, key).getId();
    }

    public List<Long> resolveActiveIds(List<Long> ids, List<String> keys) {
        if (ids == null && keys == null) {
            return null;
        }
        if ((ids != null && ids.isEmpty()) || (keys != null && keys.isEmpty())) {
            throw new IllegalArgumentException("Collection scope must not be empty");
        }

        List<Long> resolvedIds = ids == null
                ? null
                : ids.stream()
                        .map(id -> requireActiveUnobserved(id, null).getId())
                        .toList();
        List<Long> resolvedKeys = keys == null
                ? null
                : resolveActiveKeyIds(keys);
        if (resolvedIds != null && resolvedKeys != null
                && !new LinkedHashSet<>(resolvedIds).equals(new LinkedHashSet<>(resolvedKeys))) {
            throw new IllegalArgumentException(
                    "collectionIds and collectionKeys identify different collections");
        }
        List<Long> result = resolvedKeys != null ? resolvedKeys : resolvedIds;
        List<Long> uniqueResult = List.copyOf(new LinkedHashSet<>(result));
        uniqueResult.forEach(
                IntegrationObservationContext::addAuthorizedCollection);
        return uniqueResult;
    }

    private List<Long> resolveActiveKeyIds(List<String> keys) {
        LinkedHashSet<String> requestedKeys = new LinkedHashSet<>();
        for (String key : keys) {
            if (key == null || !CollectionKeyValidator.isValid(key)) {
                throw new IllegalArgumentException(
                        "collectionKey must contain 1-128 visible ASCII characters");
            }
            requestedKeys.add(key);
        }

        Map<String, RagCollection> foundByKey = new HashMap<>();
        repository.findAllByCollectionKeyInAndDeletedFalse(requestedKeys)
                .forEach(collection ->
                        foundByKey.put(collection.getCollectionKey(), collection));

        List<Long> resolved = new java.util.ArrayList<>(requestedKeys.size());
        for (String key : requestedKeys) {
            RagCollection collection = foundByKey.get(key);
            if (collection == null) {
                if (repository.findByCollectionKey(key)
                        .filter(existing -> existing.getPurgedAt() != null)
                        .isPresent()) {
                    throw new RagException(
                            ErrorCode.COLLECTION_ALREADY_RETIRED,
                            "Collection is permanently retired");
                }
                throw new RagException(
                        ErrorCode.COLLECTION_NOT_FOUND,
                        "Collection not found: collectionKey=" + key);
            }
            resolved.add(collection.getId());
        }
        return List.copyOf(resolved);
    }

    /**
     * Resolve keys only against a caller's already-authorized Collection IDs.
     * This avoids a global key lookup for restricted callers.
     */
    public List<Long> resolveActiveIdsWithinAllowed(
            List<String> keys, Collection<Long> allowedIds) {
        if (keys == null) {
            return null;
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("Collection scope must not be empty");
        }
        Map<String, Long> allowedKeys = new LinkedHashMap<>();
        Map<String, RagCollection> allowedCollections = new LinkedHashMap<>();
        repository.findAllById(allowedIds).stream()
                .forEach(collection -> {
                    allowedCollections.put(
                            collection.getCollectionKey(), collection);
                    if (!Boolean.TRUE.equals(collection.getDeleted())) {
                        allowedKeys.put(
                                collection.getCollectionKey(),
                                collection.getId());
                    }
                });

        LinkedHashSet<Long> resolved = new LinkedHashSet<>();
        for (String key : keys) {
            if (key == null || !CollectionKeyValidator.isValid(key)) {
                throw new IllegalArgumentException(
                        "collectionKey must contain 1-128 visible ASCII characters");
            }
            Long id = allowedKeys.get(key);
            if (id == null) {
                RagCollection existing = allowedCollections.get(key);
                if (existing != null && existing.getPurgedAt() != null) {
                    throw new RagException(
                            ErrorCode.COLLECTION_ALREADY_RETIRED,
                            "Collection is permanently retired");
                }
                throw new RagException(
                        ErrorCode.COLLECTION_NOT_FOUND,
                        "Collection not found: collectionKey=" + key);
            }
            resolved.add(id);
        }
        List<Long> result = List.copyOf(resolved);
        result.forEach(IntegrationObservationContext::addAuthorizedCollection);
        return result;
    }

    public Map<Long, String> mapKeys(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<Long> requestedIds = new LinkedHashSet<>(ids);
        Map<Long, RagCollection> foundById = new HashMap<>();
        repository.findAllById(requestedIds).forEach(collection ->
                foundById.put(collection.getId(), collection));

        Map<Long, String> result = new LinkedHashMap<>();
        for (Long id : requestedIds) {
            RagCollection collection = foundById.get(id);
            if (collection != null) {
                result.put(id, collection.getCollectionKey());
            }
        }
        if (result.size() != requestedIds.size()) {
            LinkedHashSet<Long> missingIds = new LinkedHashSet<>(requestedIds);
            missingIds.removeAll(result.keySet());
            log.warn("Collection key mapping is missing legacy ACL IDs: {}",
                    missingIds);
        }
        return Collections.unmodifiableMap(result);
    }

    private Optional<RagCollection> findWithinAllowed(
            String key, Collection<Long> allowedIds, boolean includeDeleted) {
        validatePair(null, key);
        Objects.requireNonNull(allowedIds, "allowedIds must not be null");
        return repository.findAllById(new LinkedHashSet<>(allowedIds)).stream()
                .filter(collection -> includeDeleted
                        || !Boolean.TRUE.equals(collection.getDeleted()))
                .filter(collection -> key.equals(collection.getCollectionKey()))
                .findFirst();
    }

    public record ActiveCollectionToken(Long collectionId, long version) {
    }

    private void validatePair(Long id, String key) {
        if (id == null && key == null) {
            throw new IllegalArgumentException("collectionId or collectionKey is required");
        }
        if (id != null && id <= 0) {
            throw new IllegalArgumentException("collectionId must be positive");
        }
        if (key != null && !CollectionKeyValidator.isValid(key)) {
            throw new IllegalArgumentException(
                    "collectionKey must contain 1-128 visible ASCII characters");
        }
    }

    private RagCollection requireMatchingId(Long id, RagCollection collection) {
        if (id != null && !id.equals(collection.getId())) {
            throw new IllegalArgumentException(
                    "collectionId and collectionKey identify different collections");
        }
        return collection;
    }

    private RagCollection requireActiveUnobserved(Long id, String key) {
        Optional<RagCollection> active = findActive(id, key);
        if (active.isPresent()) {
            return active.get();
        }
        Optional<RagCollection> existing = findIncludingDeleted(id, key);
        if (existing.filter(collection -> collection.getPurgedAt() != null)
                .isPresent()) {
            throw new RagException(
                    ErrorCode.COLLECTION_ALREADY_RETIRED,
                    "Collection is permanently retired");
        }
        throw new RagException(
                ErrorCode.COLLECTION_NOT_FOUND,
                key != null
                        ? "Collection not found: collectionKey=" + key
                        : "Collection not found: id=" + id);
    }
}
