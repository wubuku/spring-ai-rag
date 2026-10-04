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
import com.springairag.api.dto.ExternalDocumentDeleteResponse;
import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.validation.SourceNamespaceValidator;
import com.springairag.core.config.RagStructuredRecordProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.JsonbContainmentFilter;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalBranchStage;
import com.springairag.core.retrieval.RetrievalFilterValidator;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.security.ApiKeyCollectionAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
    private final HybridRetrieverService hybridRetrieverService;
    private final ReRankingService reRankingService;
    private final CollectionIdentityResolver collectionIdentityResolver;
    private final CollectionRetrievalScopeResolver retrievalScopeResolver;
    private final RagStructuredRecordProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final RetrievalFilterValidator filterValidator = new RetrievalFilterValidator();
    private final DocumentMutationService mutationService; // Batch 855：`required = false` 的 setter 已删，改成第 10 个必填构造器参数。三个使用点（upsert、tombstoneExternal、批量 upsert）全都无条件调用它，缺 bean 时抛的是裸 NPE 而不是在启动时失败
    private DocumentLifecycleService lifecycleService; // optional-claim: DocumentLifecycleService 是无条件 @Service，null 臂只在不走 Spring 装配的构造路径可达；守卫真正的职责是生命周期读取缺席时该字段留空
    private ExternalAddressRetirementService addressRetirementService; // optional-claim: ExternalAddressRetirementService 是无条件 @Service，null 臂只在不走 Spring 装配的构造路径可达；守卫真正的职责是退役校验缺席时放行，属于可跳过的旁路而非写入前置条件

    @Autowired
    public JsonRecordService(
            RagDocumentRepository documentRepository,
            DocumentVersionService documentVersionService,
            HybridRetrieverService hybridRetrieverService,
            ReRankingService reRankingService,
            CollectionIdentityResolver collectionIdentityResolver,
            com.springairag.core.config.RagProperties ragProperties,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            CollectionRetrievalScopeResolver retrievalScopeResolver,
            DocumentMutationService mutationService) {
        this.documentRepository = documentRepository;
        this.documentVersionService = documentVersionService;
        this.hybridRetrieverService = hybridRetrieverService;
        this.reRankingService = reRankingService;
        this.collectionIdentityResolver = collectionIdentityResolver;
        this.retrievalScopeResolver = retrievalScopeResolver;
        this.properties = ragProperties.getStructuredRecords();
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.mutationService = mutationService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLifecycleService(DocumentLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAddressRetirementService(
            ExternalAddressRetirementService addressRetirementService) {
        this.addressRetirementService = addressRetirementService;
    }

    public JsonRecordUpsertResponse upsert(JsonRecordUpsertRequest request) {
        resolveRequestCollection(request);
        validateRequest(request);
        return toUpsertResponse(mutationService.upsertJsonRecord(
                request,
                request.getCollectionId(),
                requestCollectionKey(request),
                null,
                null));
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
                JsonRecordUpsertResponse response = upsert(request);
                results.add(response);
                switch (response.action()) {
                    case "CREATED" -> created++;
                    case "UPDATED" -> updated++;
                    default -> unchanged++;
                }
                if ("FAILED".equals(response.embeddingStatus())) {
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
        RetrievalFilters filters =
                filterValidator.fromJsonRecordRequest(request);
        RetrievalScope retrievalScope;
        if (retrievalScopeResolver != null) {
            retrievalScope = retrievalScopeResolver.resolve(
                    CollectionScopeMode.SELECTED_COLLECTIONS,
                    request.getCollectionIds(),
                    request.getCollectionKeys(),
                    null,
                    RagDocument.JSON_RECORD,
                    ApiKeyCollectionAccess.currentPolicy());
        } else {
            List<Long> collectionIds = ApiKeyCollectionAccess.resolveCollectionIds(
                    request.getCollectionIds(),
                    request.getCollectionKeys(),
                    ApiKeyCollectionAccess.currentPolicy(),
                    collectionIdentityResolver);
            retrievalScope = RetrievalScope.selectedCollections(
                    collectionIds, null, RagDocument.JSON_RECORD);
        }
        request.setCollectionIds(retrievalScope.collectionIds());
        return searchAuthorized(
                request.getQuery(),
                filters,
                null,
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
        return searchAuthorized(
                query, RetrievalFilters.none(), payloadContains,
                authorizedScope, requestedConfig);
    }

    /**
     * 调用者 filter 与 tool 额外 payload 条件取 AND，禁止 merge 覆盖。
     */
    public JsonRecordSearchResponse searchAuthorized(
            String query,
            RetrievalFilters callerFilters,
            JsonNode extraPayloadContains,
            RetrievalScope authorizedScope,
            RetrievalConfig requestedConfig) {
        return searchAuthorizedDetailed(
                query,
                callerFilters,
                extraPayloadContains,
                authorizedScope,
                requestedConfig).response();
    }

    public DetailedSearchResult searchAuthorizedDetailed(
            String query,
            RetrievalFilters callerFilters,
            JsonNode extraPayloadContains,
            RetrievalScope authorizedScope,
            RetrievalConfig requestedConfig) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (query.length() > 10_000) {
            throw new IllegalArgumentException(
                    "query must not exceed 10000 characters");
        }
        RetrievalFilters filters = filterValidator.narrowWithPayload(
                callerFilters, extraPayloadContains);
        RetrievalScope retrievalScope = jsonRecordScope(authorizedScope);

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
        RetrievalOutcome outcome = hybridRetrieverService.searchInScopeDetailed(
                query, retrievalScope, null,
                limit, effectiveConfig, filters);
        List<RetrievalResult> ranked = outcome.results();
        if (config.isUseRerank() && !ranked.isEmpty()) {
            long rerankStartedAt = System.nanoTime();
            List<RetrievalResult> beforeRerank = ranked;
            boolean degraded = false;
            String errorCode = null;
            try {
                ranked = reRankingService.rerank(
                        query, beforeRerank, limit);
            } catch (RuntimeException e) {
                ranked = ReRankingService.limitResults(beforeRerank, limit);
                degraded = true;
                errorCode = e.getClass().getSimpleName();
            }
            ranked = ReRankingService.limitResults(ranked, limit);
            outcome = outcome.withRerank(
                    new RetrievalBranchStage(
                            RetrievalBranchStage.RERANK,
                            "rerank",
                            degraded
                                    ? RetrievalBranchStage.ERROR
                                    : RetrievalBranchStage.SUCCESS,
                            (System.nanoTime() - rerankStartedAt) / 1_000_000L,
                            beforeRerank.size(),
                            ranked.size(),
                            errorCode),
                    ranked,
                    degraded);
        }

        LinkedHashMap<Long, RetrievalResult> uniqueRanked = new LinkedHashMap<>();
        for (RetrievalResult result : ranked) {
            Long documentId = parseDocumentId(result.getDocumentId());
            if (documentId != null) {
                uniqueRanked.putIfAbsent(documentId, result);
            }
        }
        if (uniqueRanked.isEmpty()) {
            return new DetailedSearchResult(
                    new JsonRecordSearchResponse(query, List.of()),
                    outcome,
                    List.of());
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
        List<RetrievalResult> traceResults = new ArrayList<>();
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
            traceResults.add(rankedResult);
            if (results.size() == limit) {
                break;
            }
        }
        return new DetailedSearchResult(
                new JsonRecordSearchResponse(query, results),
                outcome,
                traceResults);
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
        return filterValidator.validateObject(filter, "payloadContains");
    }

    public JsonRecordDetailResponse getDetail(Long documentId) {
        RagDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!RagDocument.JSON_RECORD.equals(doc.getDocumentType())) {
            throw new DocumentNotFoundException(documentId);
        }
        ApiKeyCollectionAccess.requireDocumentAccess(
                doc, ApiKeyCollectionAccess.currentPolicy());
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
                doc.getMetadata(),
                doc.getSourceNamespace(),
                doc.getSourceRevision(),
                doc.getDocumentRevision(),
                lifecycleService == null ? null : lifecycleService.read(doc));
    }

    public JsonRecordDetailResponse getByExternalIdentity(
            String collectionKey,
            String sourceNamespace,
            String externalId) {
        List<Long> collections = ApiKeyCollectionAccess.resolveCollectionIds(
                null,
                List.of(collectionKey),
                ApiKeyCollectionAccess.currentPolicy(),
                collectionIdentityResolver);
        if (collections.size() != 1) {
            throw new IllegalArgumentException(
                    "Exactly one Collection must be provided");
        }
        long collectionId = collections.getFirst();
        String normalizedNamespace = normalizeNamespace(sourceNamespace);
        String normalizedExternalId = requireExternalId(externalId);
        if (addressRetirementService != null) {
            addressRetirementService.requireNotRetired(
                    collectionId, normalizedNamespace, normalizedExternalId);
        }
        RagDocument document = documentRepository
                .findByCollectionIdAndSourceNamespaceAndDocumentTypeAndExternalId(
                        collectionId,
                        normalizedNamespace,
                        RagDocument.JSON_RECORD,
                        normalizedExternalId)
                .orElseThrow(() -> new DocumentNotFoundException(-1L));
        return getDetail(document.getId());
    }

    public ExternalDocumentDeleteResponse sourceDelete(
            String collectionKey,
            String sourceNamespace,
            String externalId,
            String sourceRevision,
            String expectedSourceRevision) {
        return mutationService.tombstoneExternal(
                collectionKey,
                sourceNamespace,
                externalId,
                sourceRevision,
                expectedSourceRevision,
                true);
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
                collectionId, ApiKeyCollectionAccess.currentPolicy());

        return toUpsertResponse(mutationService.upsertJsonRecord(
                request,
                collectionId,
                requestCollectionKey(request),
                imported.getOriginalFilename(),
                imported.getEnabled()));
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
                ApiKeyCollectionAccess.currentPolicy(),
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
            DocumentMutationService.JsonMutationResult result) {
        RagDocument document = result.document();
        EmbeddingDispatchService.Result dispatch = result.dispatch();
        return new JsonRecordUpsertResponse(
                document.getId(),
                document.getCollectionId(),
                collectionIdentityResolver.mapKeys(
                        List.of(document.getCollectionId()))
                        .get(document.getCollectionId()),
                document.getExternalId(),
                result.action(),
                result.contentChanged(),
                result.payloadChanged(),
                result.versionNumber(),
                result.lifecycle().embeddingStatus(),
                result.lifecycle().activeEmbeddingProfileKey(),
                dispatch == null ? null : dispatch.error(),
                dispatch == null ? "NONE" : dispatch.action().name(),
                dispatch == null ? null : dispatch.embeddingJobId(),
                dispatch == null ? null : dispatch.embeddingBatchId(),
                document.getSourceNamespace(),
                document.getSourceRevision(),
                document.getDocumentRevision(),
                result.lifecycle());
    }

    private String normalizeNamespace(String value) {
        String normalized = value == null || value.isBlank()
                ? "default" : value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException(
                    "sourceNamespace must not exceed 128 characters");
        }
        if (!SourceNamespaceValidator.isValid(normalized)) {
            throw new IllegalArgumentException(
                    "sourceNamespace must contain visible ASCII only");
        }
        return normalized;
    }

    private String requireExternalId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "externalId must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > 255) {
            throw new IllegalArgumentException(
                    "externalId must not exceed 255 characters");
        }
        return normalized;
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

    public record DetailedSearchResult(
            JsonRecordSearchResponse response,
            RetrievalOutcome outcome,
            List<RetrievalResult> traceResults) {
        public DetailedSearchResult {
            Objects.requireNonNull(response, "response must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            traceResults = traceResults == null
                    ? List.of()
                    : List.copyOf(traceResults);
        }
    }
}
