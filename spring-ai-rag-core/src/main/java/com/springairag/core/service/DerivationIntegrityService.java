package com.springairag.core.service;

import com.springairag.api.dto.DerivationReadinessPageResponse;
import com.springairag.api.dto.DerivationReadinessResponse;
import com.springairag.api.dto.CollectionEmbeddingReadinessResponse;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.security.ApiKeyCollectionAccess;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Collection 范围派生完整性只读应用服务。 */
@Service
public class DerivationIntegrityService {

    private final DerivationIntegrityRepository repository;
    private final CollectionIdentityResolver collectionResolver;
    private final EmbeddingProfileProvider profileProvider;

    public DerivationIntegrityService(
            DerivationIntegrityRepository repository,
            CollectionIdentityResolver collectionResolver,
            EmbeddingProfileProvider profileProvider) {
        this.repository = repository;
        this.collectionResolver = collectionResolver;
        this.profileProvider = profileProvider;
    }

    public DerivationReadinessResponse summary(String collectionKey) {
        RagCollection collection = requireCollection(collectionKey);
        DerivationIntegrityRepository.Aggregate aggregate =
                repository.aggregateCollection(collection.getId());
        return new DerivationReadinessResponse(
                collection.getCollectionKey(), profileProvider.getActiveProfile().profileKey(),
                aggregate.enabledDocuments(), aggregate.readyDocuments(),
                aggregate.keywordOnlyDocuments(), aggregate.indexingDocuments(),
                aggregate.localUnavailableDocuments(),
                aggregate.vectorRepairNeededDocuments(),
                aggregate.notRequestedDocuments(), aggregate.corruptDocuments(),
                aggregate.disabledDocuments(), Instant.now());
    }

    public CollectionEmbeddingReadinessResponse embeddingReadiness(String collectionKey) {
        RagCollection collection = requireCollection(collectionKey);
        DerivationIntegrityRepository.EmbeddingAggregate aggregate =
                repository.aggregateEmbeddingReadiness(collection.getId());
        return new CollectionEmbeddingReadinessResponse(
                collection.getCollectionKey(), profileProvider.getActiveProfile().profileKey(),
                aggregate.enabledDocuments(), aggregate.freshDocuments(),
                aggregate.queuedDocuments(), aggregate.runningDocuments(),
                aggregate.failedDocuments(), aggregate.staleDocuments());
    }

    public DerivationReadinessPageResponse details(
            String collectionKey, String bucket, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be non-negative and size must be 1-100");
        }
        RagCollection collection = requireCollection(collectionKey);
        java.util.List<com.springairag.api.dto.DerivationReadinessDocument> documents =
                repository.scanCollection(collection.getId(), bucket, page * size, size).stream()
                .map(DerivationIntegrityRepository.Snapshot::toResponse).toList();
        return new DerivationReadinessPageResponse(
                collection.getCollectionKey(), bucket, page, size,
                repository.countCollection(collection.getId(), bucket), documents);
    }

    RagCollection requireCollection(String collectionKey) {
        ApiAccessPolicy caller = ApiKeyCollectionAccess.currentPolicy();
        return ApiKeyCollectionAccess.requireActiveCollectionByKey(
                collectionKey, caller, collectionResolver);
    }
}
