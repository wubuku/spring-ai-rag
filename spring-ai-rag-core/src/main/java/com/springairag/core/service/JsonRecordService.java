package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordBatchUpsertResponse;
import com.springairag.api.dto.JsonRecordDetailResponse;
import com.springairag.api.dto.JsonRecordSearchRequest;
import com.springairag.api.dto.JsonRecordSearchResponse;
import com.springairag.api.dto.JsonRecordSearchResult;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagStructuredRecordProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.exception.StructuredRecordConflictException;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.JsonbContainmentFilter;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.util.DigestUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persistence and retrieval service for caller-owned JSON structured records.
 */
@Service
public class JsonRecordService {

    private final RagDocumentRepository documentRepository;
    private final DocumentVersionService documentVersionService;
    private final DocumentEmbedService documentEmbedService;
    private final HybridRetrieverService hybridRetrieverService;
    private final ReRankingService reRankingService;
    private final EmbeddingProfileProvider embeddingProfileProvider;
    private final CollectionIdentityResolver collectionIdentityResolver;
    private final CollectionRetrievalScopeResolver retrievalScopeResolver;
    private final RagStructuredRecordProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public JsonRecordService(
            RagDocumentRepository documentRepository,
            DocumentVersionService documentVersionService,
            DocumentEmbedService documentEmbedService,
            HybridRetrieverService hybridRetrieverService,
            ReRankingService reRankingService,
            EmbeddingProfileProvider embeddingProfileProvider,
            CollectionIdentityResolver collectionIdentityResolver,
            com.springairag.core.config.RagProperties ragProperties,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            CollectionRetrievalScopeResolver retrievalScopeResolver,
            @Nullable PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.documentVersionService = documentVersionService;
        this.documentEmbedService = documentEmbedService;
        this.hybridRetrieverService = hybridRetrieverService;
        this.reRankingService = reRankingService;
        this.embeddingProfileProvider = embeddingProfileProvider;
        this.collectionIdentityResolver = collectionIdentityResolver;
        this.retrievalScopeResolver = retrievalScopeResolver;
        this.properties = ragProperties.getStructuredRecords();
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionManager == null
                ? null
                : new TransactionTemplate(transactionManager);
    }

    JsonRecordService(
            RagDocumentRepository documentRepository,
            DocumentVersionService documentVersionService,
            DocumentEmbedService documentEmbedService,
            HybridRetrieverService hybridRetrieverService,
            ReRankingService reRankingService,
            EmbeddingProfileProvider embeddingProfileProvider,
            CollectionIdentityResolver collectionIdentityResolver,
            com.springairag.core.config.RagProperties ragProperties,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            @Nullable PlatformTransactionManager transactionManager) {
        this(documentRepository, documentVersionService, documentEmbedService,
                hybridRetrieverService, reRankingService, embeddingProfileProvider,
                collectionIdentityResolver, ragProperties, objectMapper, jdbcTemplate,
                null, transactionManager);
    }

    public JsonRecordUpsertResponse upsert(JsonRecordUpsertRequest request) {
        resolveRequestCollection(request);
        validateRequest(request);

        PersistedRecord persisted = persist(request);
        EmbeddingOutcome embedding = embedIfRequested(persisted, request.isEmbed());
        return toUpsertResponse(persisted, embedding);
    }

    public JsonRecordBatchUpsertResponse batchUpsert(List<JsonRecordUpsertRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (requests.size() > properties.getMaxBatchSize()) {
            throw new IllegalArgumentException(
                    "JSON record batch is limited to " + properties.getMaxBatchSize() + " items");
        }

        int totalPayloadBytes = requests.stream()
                .mapToInt(this::measurePayloadBytes)
                .sum();
        if (totalPayloadBytes > properties.getMaxBatchPayloadBytes()) {
            throw new IllegalArgumentException(
                    "JSON record batch payload exceeds "
                            + properties.getMaxBatchPayloadBytes() + " bytes");
        }

        List<JsonRecordUpsertResponse> results = new ArrayList<>(requests.size());
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int persistenceFailed = 0;
        int embeddingFailed = 0;

        for (JsonRecordUpsertRequest request : requests) {
            try {
                resolveRequestCollection(request);
                validateRequest(request);
                PersistedRecord persisted = persist(request);
                EmbeddingOutcome embedding = embedIfRequested(persisted, request.isEmbed());
                JsonRecordUpsertResponse response = toUpsertResponse(persisted, embedding);
                results.add(response);
                switch (persisted.action()) {
                    case "CREATED" -> created++;
                    case "UPDATED" -> updated++;
                    default -> unchanged++;
                }
                if ("FAILED".equals(embedding.status())) {
                    embeddingFailed++;
                }
            } catch (RuntimeException e) {
                persistenceFailed++;
                results.add(failedResponse(request, e));
            }
        }

        return new JsonRecordBatchUpsertResponse(
                results,
                new JsonRecordBatchUpsertResponse.Summary(
                        requests.size(), created, updated, unchanged,
                        persistenceFailed, embeddingFailed));
    }

    public JsonRecordSearchResponse search(JsonRecordSearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.getCollectionIds() == null && request.getCollectionKeys() == null) {
            throw new IllegalArgumentException(
                    "collectionKeys or collectionIds must be provided");
        }
        RetrievalScope retrievalScope;
        if (retrievalScopeResolver != null) {
            retrievalScope = retrievalScopeResolver.resolve(
                    CollectionScopeMode.SELECTED_COLLECTIONS,
                    request.getCollectionIds(),
                    request.getCollectionKeys(),
                    null,
                    RagDocument.JSON_RECORD,
                    ApiKeyCollectionAccess.currentKey());
        } else {
            List<Long> collectionIds = ApiKeyCollectionAccess.resolveCollectionIds(
                    request.getCollectionIds(),
                    request.getCollectionKeys(),
                    ApiKeyCollectionAccess.currentKey(),
                    collectionIdentityResolver);
            retrievalScope = RetrievalScope.selectedCollections(
                    collectionIds, null, RagDocument.JSON_RECORD);
        }
        if (retrievalScope.matchNone()
                || retrievalScope.collectionIds().isEmpty()) {
            return new JsonRecordSearchResponse(request.getQuery(), List.of());
        }
        request.setCollectionIds(retrievalScope.collectionIds());
        return searchAuthorized(
                request.getQuery(),
                request.getPayloadContains(),
                retrievalScope,
                request.getConfig());
    }

    /**
     * 使用服务端已经授权的不可变 scope 检索 JSON 记录。
     */
    public JsonRecordSearchResponse searchAuthorized(
            String query,
            JsonNode payloadContains,
            RetrievalScope authorizedScope,
            RetrievalConfig requestedConfig) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (query.length() > 10_000) {
            throw new IllegalArgumentException(
                    "query must not exceed 10000 characters");
        }
        RetrievalScope retrievalScope = jsonRecordScope(authorizedScope);
        if (retrievalScope.matchNone()) {
            return new JsonRecordSearchResponse(query, List.of());
        }
        JsonbContainmentFilter payloadFilter =
                validatePayloadFilter(payloadContains);

        RetrievalConfig config = requestedConfig == null
                ? RetrievalConfig.builder().build()
                : requestedConfig;
        int limit = Math.min(config.getMaxResults(), properties.getMaxSearchResults());
        if (limit < 1) {
            throw new IllegalArgumentException("maxResults must be at least 1");
        }

        RetrievalConfig effectiveConfig = RetrievalConfig.builder()
                .maxResults(limit)
                .minScore(config.getMinScore())
                .useHybridSearch(config.isUseHybridSearch())
                .useRerank(config.isUseRerank())
                .vectorWeight(config.getVectorWeight())
                .fulltextWeight(config.getFulltextWeight())
                .build();
        List<RetrievalResult> ranked = hybridRetrieverService.searchInScope(
                query, retrievalScope, null,
                limit, effectiveConfig, payloadFilter);
        if (config.isUseRerank() && !ranked.isEmpty()) {
            ranked = reRankingService.rerank(query, ranked, limit);
        }

        LinkedHashMap<Long, RetrievalResult> uniqueRanked = new LinkedHashMap<>();
        for (RetrievalResult result : ranked) {
            Long documentId = parseDocumentId(result.getDocumentId());
            if (documentId != null) {
                uniqueRanked.putIfAbsent(documentId, result);
            }
        }
        if (uniqueRanked.isEmpty()) {
            return new JsonRecordSearchResponse(query, List.of());
        }

        List<RagDocument> documents = documentRepository
                .findByIdInAndDocumentTypeAndEnabledTrue(
                        new ArrayList<>(uniqueRanked.keySet()), RagDocument.JSON_RECORD);
        Map<Long, RagDocument> byId = documents.stream()
                .filter(doc -> scopeAllows(retrievalScope, doc))
                .collect(java.util.stream.Collectors.toMap(
                        RagDocument::getId, doc -> doc, (left, right) -> left));
        List<Long> resultCollectionIds = byId.values().stream()
                .map(RagDocument::getCollectionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> collectionKeys = resultCollectionIds.isEmpty()
                ? Map.of()
                : collectionIdentityResolver.mapKeys(resultCollectionIds);

        List<JsonRecordSearchResult> results = new ArrayList<>();
        for (Map.Entry<Long, RetrievalResult> entry : uniqueRanked.entrySet()) {
            RagDocument doc = byId.get(entry.getKey());
            if (doc == null) {
                continue;
            }
            RetrievalResult rankedResult = entry.getValue();
            results.add(new JsonRecordSearchResult(
                    doc.getId(),
                    doc.getCollectionId(),
                    collectionKeys.get(doc.getCollectionId()),
                    doc.getExternalId(),
                    doc.getTitle(),
                    doc.getSource(),
                    doc.getContent(),
                    doc.getJsonbPayload(),
                    rankedResult.getScore(),
                    rankedResult.getVectorScore(),
                    rankedResult.getFulltextScore(),
                    doc.getMetadata()));
            if (results.size() == limit) {
                break;
            }
        }
        return new JsonRecordSearchResponse(query, results);
    }

    private RetrievalScope jsonRecordScope(RetrievalScope authorizedScope) {
        RetrievalScope scope = authorizedScope != null
                ? authorizedScope
                : RetrievalScope.noMatches();
        if (scope.matchNone()) {
            return RetrievalScope.noMatches();
        }
        if (scope.documentType() != null
                && !RagDocument.JSON_RECORD.equals(scope.documentType())) {
            return RetrievalScope.noMatches();
        }
        return new RetrievalScope(
                scope.collectionFilter(),
                scope.collectionIds(),
                scope.documentIds(),
                RagDocument.JSON_RECORD,
                false);
    }

    private boolean scopeAllows(RetrievalScope scope, RagDocument document) {
        if (document == null
                || !Boolean.TRUE.equals(document.getEnabled())
                || !RagDocument.JSON_RECORD.equals(document.getDocumentType())) {
            return false;
        }
        if (!scope.documentIds().isEmpty()
                && !scope.documentIds().contains(document.getId())) {
            return false;
        }
        return switch (scope.collectionFilter()) {
            case NONE -> true;
            case ANY_ASSIGNED -> document.getCollectionId() != null;
            case SELECTED -> scope.collectionIds().contains(
                    document.getCollectionId());
        };
    }

    private JsonbContainmentFilter validatePayloadFilter(JsonNode filter) {
        if (filter == null) {
            return null;
        }
        if (!filter.isObject()) {
            throw new IllegalArgumentException(
                    "payloadContains must be a JSON object");
        }
        if (filter.isEmpty()) {
            throw new IllegalArgumentException(
                    "payloadContains must not be an empty object");
        }
        byte[] serialized = serializePayload(filter);
        if (serialized.length > properties.getMaxPayloadFilterBytes()) {
            throw new IllegalArgumentException(
                    "payloadContains exceeds "
                            + properties.getMaxPayloadFilterBytes() + " bytes");
        }
        int depth = jsonDepth(filter);
        if (depth > properties.getMaxPayloadFilterDepth()) {
            throw new IllegalArgumentException(
                    "payloadContains depth exceeds "
                            + properties.getMaxPayloadFilterDepth());
        }
        return new JsonbContainmentFilter(
                new String(serialized, StandardCharsets.UTF_8));
    }

    private int jsonDepth(JsonNode node) {
        if (node == null || !node.isContainerNode()) {
            return 0;
        }
        int childDepth = 0;
        for (JsonNode child : node) {
            childDepth = Math.max(childDepth, jsonDepth(child));
        }
        return 1 + childDepth;
    }

    public JsonRecordDetailResponse getDetail(Long documentId) {
        RagDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!RagDocument.JSON_RECORD.equals(doc.getDocumentType())) {
            throw new DocumentNotFoundException(documentId);
        }
        ApiKeyCollectionAccess.requireDocumentAccess(
                doc, ApiKeyCollectionAccess.currentKey());
        int versionNumber = documentVersionService.getLatestVersion(documentId)
                .map(RagDocumentVersion::getVersionNumber)
                .orElse(0);
        return new JsonRecordDetailResponse(
                doc.getId(),
                doc.getCollectionId(),
                collectionIdentityResolver.mapKeys(
                        List.of(doc.getCollectionId())).get(doc.getCollectionId()),
                doc.getExternalId(),
                doc.getTitle(),
                doc.getSource(),
                doc.getContent(),
                doc.getJsonbPayload(),
                doc.getContentHash(),
                doc.getProcessingStatus(),
                Boolean.TRUE.equals(doc.getEnabled()),
                doc.getCreatedAt(),
                doc.getUpdatedAt(),
                versionNumber,
                doc.getMetadata());
    }

    private PersistedRecord persist(JsonRecordUpsertRequest request) {
        return persist(request, null, null);
    }

    /**
     * Imports an exported JSON record through the same validation, identity,
     * version and persistence path as the public upsert API.
     */
    public JsonRecordUpsertResponse importRecord(
            Long collectionId, CollectionImportRequest.ImportedDocument imported) {
        if (imported == null) {
            throw new IllegalArgumentException("Imported document must not be null");
        }
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(collectionId);
        request.setExternalId(imported.getExternalId());
        request.setTitle(imported.getTitle());
        request.setRetrievalText(imported.getContent());
        request.setJsonbPayload(imported.getJsonbPayload());
        request.setSource(imported.getSource());
        request.setMetadata(imported.getMetadata());
        request.setEmbed(false);
        if (imported.getOriginalFilename() != null
                && imported.getOriginalFilename().length() > 255) {
            throw new IllegalArgumentException(
                    "originalFilename must not exceed 255 characters");
        }
        validateRequest(request);
        ApiKeyCollectionAccess.requireCollectionId(
                collectionId, ApiKeyCollectionAccess.currentKey());

        PersistedRecord persisted = persist(
                request, imported.getOriginalFilename(), imported.getEnabled());
        return toUpsertResponse(
                persisted, new EmbeddingOutcome("NOT_REQUESTED", null, null));
    }

    private PersistedRecord persist(
            JsonRecordUpsertRequest request,
            String originalFilename,
            Boolean enabledOverride) {
        try {
            if (transactionTemplate == null) {
                return persistInTransaction(request, originalFilename, enabledOverride);
            }
            return transactionTemplate.execute(status ->
                    persistInTransaction(request, originalFilename, enabledOverride));
        } catch (DataIntegrityViolationException e) {
            throw new StructuredRecordConflictException(
                    "Structured record identity already exists or is conflicting", e);
        }
    }

    private PersistedRecord persistInTransaction(
            JsonRecordUpsertRequest request,
            String originalFilename,
            Boolean enabledOverride) {
        String externalId = request.getExternalId().trim();
        lockActiveCollection(request.getCollectionId());
        lockIdentity(request.getCollectionId(), externalId);
        String contentHash = DigestUtils.sha256(request.getRetrievalText());
        JsonNode payload = request.getJsonbPayload().deepCopy();

        RagDocument doc = documentRepository
                .findByCollectionIdAndDocumentTypeAndExternalId(
                        request.getCollectionId(), RagDocument.JSON_RECORD, externalId)
                .orElse(null);
        boolean created = doc == null;
        boolean contentChanged;
        boolean payloadChanged;
        boolean changed;

        if (created) {
            doc = new RagDocument();
            doc.setCollectionId(request.getCollectionId());
            doc.setDocumentType(RagDocument.JSON_RECORD);
            doc.setExternalId(externalId);
            doc.setContentHash(contentHash);
            doc.setProcessingStatus("PENDING");
            contentChanged = true;
            payloadChanged = true;
            changed = true;
        } else {
            contentChanged = !Objects.equals(doc.getContent(), request.getRetrievalText());
            payloadChanged = !Objects.equals(doc.getJsonbPayload(), payload);
            changed = contentChanged
                    || payloadChanged
                    || !Objects.equals(doc.getTitle(), request.getTitle())
                    || !Objects.equals(doc.getSource(), request.getSource())
                    || !Objects.equals(doc.getMetadata(), request.getMetadata())
                    || (originalFilename != null
                            && !Objects.equals(doc.getOriginalFilename(), originalFilename))
                    || (enabledOverride != null
                            && !Objects.equals(doc.getEnabled(), enabledOverride));
        }

        if (changed) {
            doc.setTitle(request.getTitle());
            doc.setContent(request.getRetrievalText());
            doc.setSource(request.getSource());
            doc.setMetadata(request.getMetadata());
            doc.setJsonbPayload(payload);
            doc.setSize(request.getRetrievalText().getBytes(StandardCharsets.UTF_8).length
                    * 1L);
            if (originalFilename != null) {
                doc.setOriginalFilename(originalFilename);
            }
            if (enabledOverride != null) {
                doc.setEnabled(enabledOverride);
            }
            if (contentChanged || created) {
                doc.setContentHash(contentHash);
                doc.setProcessingStatus("PENDING");
                doc.setProcessingError(null);
            }
            doc = documentRepository.saveAndFlush(doc);
        }

        RagDocumentVersion version = null;
        if (created || changed) {
            version = documentVersionService.forceRecordVersion(
                    doc,
                    created ? "CREATE" : "UPDATE",
                    created ? "JSON structured record created"
                            : changedFields(contentChanged, payloadChanged, doc));
        }
        int versionNumber = version != null
                ? version.getVersionNumber()
                : documentVersionService.getLatestVersion(doc.getId())
                        .map(RagDocumentVersion::getVersionNumber)
                        .orElse(0);
        return new PersistedRecord(
                doc,
                created ? "CREATED" : changed ? "UPDATED" : "UNCHANGED",
                contentChanged,
                payloadChanged,
                versionNumber);
    }

    private void lockIdentity(Long collectionId, String externalId) {
        String lockKey = collectionId + ":external-document:" + externalId;
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, lockKey);
                statement.execute();
            }
            return null;
        });
    }

    private void lockActiveCollection(Long collectionId) {
        if (transactionTemplate != null) {
            collectionIdentityResolver.requireActiveForShare(collectionId);
        }
    }

    private String changedFields(
            boolean contentChanged, boolean payloadChanged, RagDocument doc) {
        List<String> fields = new ArrayList<>();
        if (contentChanged) {
            fields.add("retrievalText");
        }
        if (payloadChanged) {
            fields.add("jsonbPayload");
        }
        if (fields.isEmpty()) {
            fields.add("metadata/title/source");
        }
        return "JSON structured record updated: " + String.join(",", fields);
    }

    private EmbeddingOutcome embedIfRequested(PersistedRecord persisted, boolean embed) {
        if (!embed) {
            return new EmbeddingOutcome("NOT_REQUESTED", null, null);
        }
        EmbeddingProfile activeProfile = embeddingProfileProvider.getActiveProfile();
        if (!"CREATED".equals(persisted.action())
                && !persisted.contentChanged()
                && documentEmbedService.hasFreshEmbedding(persisted.document())) {
            return new EmbeddingOutcome("CACHED", activeProfile.profileKey(), null);
        }
        try {
            Map<String, Object> result = documentEmbedService.embedDocument(
                    persisted.document().getId(), false);
            String status = String.valueOf(result.getOrDefault("status", "FAILED"));
            String profileKey = String.valueOf(
                    result.getOrDefault("embeddingProfileKey",
                            activeProfile.profileKey()));
            String error = result.get("error") == null
                    ? null
                    : safeError(String.valueOf(result.get("error")));
            return new EmbeddingOutcome(
                    "COMPLETED".equals(status) ? "COMPLETED"
                            : "CACHED".equals(status) ? "CACHED" : "FAILED",
                    profileKey, error);
        } catch (RuntimeException e) {
            return new EmbeddingOutcome(
                    "FAILED",
                    activeProfile.profileKey(),
                    safeError(e));
        }
    }

    private void validateRequest(JsonRecordUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.getCollectionId() == null || request.getCollectionId() <= 0) {
            throw new IllegalArgumentException("collectionId must be positive");
        }
        if (request.getExternalId() == null || request.getExternalId().trim().isEmpty()) {
            throw new IllegalArgumentException("externalId must not be blank");
        }
        if (request.getExternalId().trim().length() > 255) {
            throw new IllegalArgumentException("externalId must not exceed 255 characters");
        }
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (request.getTitle().length() > 255) {
            throw new IllegalArgumentException("title must not exceed 255 characters");
        }
        if (request.getRetrievalText() == null || request.getRetrievalText().isBlank()) {
            throw new IllegalArgumentException("retrievalText must not be blank");
        }
        if (request.getRetrievalText().length() > properties.getMaxRetrievalTextChars()) {
            throw new IllegalArgumentException(
                    "retrievalText exceeds " + properties.getMaxRetrievalTextChars() + " characters");
        }
        if (request.getJsonbPayload() == null || request.getJsonbPayload().isNull()) {
            throw new IllegalArgumentException("jsonbPayload must be a non-null JSON value");
        }
        int payloadBytes = serializePayload(request.getJsonbPayload()).length;
        if (payloadBytes > properties.getMaxJsonbPayloadBytes()) {
            throw new IllegalArgumentException(
                    "jsonbPayload exceeds " + properties.getMaxJsonbPayloadBytes() + " bytes");
        }
        if (request.getSource() != null && request.getSource().length() > 255) {
            throw new IllegalArgumentException("source must not exceed 255 characters");
        }
    }

    private void resolveRequestCollection(JsonRecordUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        Long collectionId = request.getCollectionId();
        String collectionKey = request.getCollectionKey();
        if (collectionId == null && collectionKey == null) {
            throw new IllegalArgumentException(
                    "collectionKey or collectionId must be provided");
        }
        List<Long> resolved = ApiKeyCollectionAccess.resolveCollectionIds(
                collectionId == null ? null : List.of(collectionId),
                collectionKey == null ? null : List.of(collectionKey),
                ApiKeyCollectionAccess.currentKey(),
                collectionIdentityResolver);
        if (resolved == null || resolved.size() != 1) {
            throw new IllegalArgumentException(
                    "Exactly one Collection must be provided");
        }
        request.setCollectionId(resolved.getFirst());
    }

    private int measurePayloadBytes(JsonRecordUpsertRequest request) {
        if (request == null || request.getJsonbPayload() == null) {
            return 0;
        }
        return serializePayload(request.getJsonbPayload()).length;
    }

    private byte[] serializePayload(JsonNode payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("jsonbPayload cannot be serialized", e);
        }
    }

    private JsonRecordUpsertResponse toUpsertResponse(
            PersistedRecord persisted, EmbeddingOutcome embedding) {
        return new JsonRecordUpsertResponse(
                persisted.document().getId(),
                persisted.document().getCollectionId(),
                collectionIdentityResolver.mapKeys(
                        List.of(persisted.document().getCollectionId()))
                        .get(persisted.document().getCollectionId()),
                persisted.document().getExternalId(),
                persisted.action(),
                persisted.contentChanged(),
                persisted.payloadChanged(),
                persisted.versionNumber(),
                embedding.status(),
                embedding.profileKey(),
                embedding.error());
    }

    private JsonRecordUpsertResponse failedResponse(
            JsonRecordUpsertRequest request, RuntimeException exception) {
        return new JsonRecordUpsertResponse(
                null,
                request == null ? null : request.getCollectionId(),
                requestCollectionKey(request),
                request == null ? null : request.getExternalId(),
                "FAILED",
                false,
                false,
                0,
                "PERSISTENCE_FAILED",
                null,
                safeError(exception));
    }

    private String requestCollectionKey(JsonRecordUpsertRequest request) {
        if (request == null) {
            return null;
        }
        if (request.getCollectionKey() != null) {
            return request.getCollectionKey();
        }
        if (request.getCollectionId() == null) {
            return null;
        }
        return collectionIdentityResolver.mapKeys(List.of(request.getCollectionId()))
                .get(request.getCollectionId());
    }

    private String safeError(RuntimeException exception) {
        return safeError(exception.getMessage() == null
                || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage());
    }

    private String safeError(String message) {
        String safeMessage = SensitiveDataMaskingConverter.maskSensitiveData(message);
        return safeMessage.length() > 500
                ? safeMessage.substring(0, 500) + "..."
                : safeMessage;
    }

    private Long parseDocumentId(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record PersistedRecord(
            RagDocument document,
            String action,
            boolean contentChanged,
            boolean payloadChanged,
            int versionNumber) {
    }

    private record EmbeddingOutcome(String status, String profileKey, String error) {
    }
}
