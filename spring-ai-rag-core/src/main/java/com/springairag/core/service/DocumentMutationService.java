package com.springairag.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.api.dto.DocumentDisableRequest;
import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.api.dto.DocumentMutationResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.DocumentRestoreRequest;
import com.springairag.api.dto.DocumentUpdateRequest;
import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.api.dto.DocumentVersionRestoreRequest;
import com.springairag.api.dto.ExternalDocumentDeleteResponse;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.dto.ExternalDocumentUpsertResponse;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.enums.DocumentDeduplicationScope;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.api.enums.DocumentRestoreVisibilityMode;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagDocumentLifecycleProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.embeddingjob.EmbeddingPolicyResolver;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.RagException;
import com.springairag.core.exception.StructuredRecordConflictException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.util.DigestUtils;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 文档业务 mutation 的唯一协调入口。
 *
 * <p>主记录、业务 revision、完整快照、派生状态和持久化 job 在同一事务中提交；
 * provider 调用只发生在事务提交之后。
 */
@Service
public class DocumentMutationService {

    private static final int MAX_EXTERNAL_TRANSACTION_ATTEMPTS = 3;

    private final RagDocumentRepository documentRepository;
    private final RagEmbeddingRepository embeddingRepository;
    private final CollectionIdentityResolver collectionIdentityResolver;
    private final DocumentVersionService versionService;
    private final EmbeddingDispatchService dispatchService;
    private final DocumentEmbedService documentEmbedService;
    private final DocumentLifecycleService lifecycleService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final RagDocumentLifecycleProperties properties;
    private final ObjectMapper objectMapper;
    private KeywordIndexPersistenceService keywordIndexPersistenceService;
    private ExternalAddressRetirementService addressRetirementService;

    public DocumentMutationService(
            RagDocumentRepository documentRepository,
            RagEmbeddingRepository embeddingRepository,
            CollectionIdentityResolver collectionIdentityResolver,
            DocumentVersionService versionService,
            EmbeddingDispatchService dispatchService,
            DocumentEmbedService documentEmbedService,
            DocumentLifecycleService lifecycleService,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
        this.collectionIdentityResolver = collectionIdentityResolver;
        this.versionService = versionService;
        this.dispatchService = dispatchService;
        this.documentEmbedService = documentEmbedService;
        this.lifecycleService = lifecycleService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = ragProperties.getDocumentLifecycle();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setKeywordIndexPersistenceService(
            KeywordIndexPersistenceService keywordIndexPersistenceService) {
        this.keywordIndexPersistenceService = keywordIndexPersistenceService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAddressRetirementService(
            ExternalAddressRetirementService addressRetirementService) {
        this.addressRetirementService = addressRetirementService;
    }

    public CreatedLocal createLocal(
            DocumentRequest request,
            Long collectionId,
            EmbeddingPolicy requestedPolicy,
            String origin) {
        return createLocal(
                request, collectionId, requestedPolicy, false, origin,
                null, null, null, null);
    }

    public CreatedLocal createLocal(
            DocumentRequest request,
            Long collectionId,
            EmbeddingPolicy requestedPolicy,
            boolean force,
            String origin,
            String idempotencyKey,
            String originalFilename,
            JsonNode jsonbPayload,
            Boolean enabledOverride) {
        EmbeddingPolicy policy = requestedPolicy == null
                ? EmbeddingPolicy.SKIP : requestedPolicy;
        validateCreate(request);
        String fingerprint = idempotencyKey == null
                ? null : localCreateFingerprint(
                        request, collectionId, policy, force,
                        originalFilename, jsonbPayload, enabledOverride);
        Prepared prepared = requireResult(transactionTemplate.execute(status -> {
            reserveCollections(null, collectionId);
            IdempotencyReservation reservation = reserveIdempotency(
                    idempotencyKey, origin, fingerprint);
            if (reservation.replayDocumentId() != null) {
                RagDocument replay = documentRepository.findById(
                                reservation.replayDocumentId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotent document result no longer exists"));
                return new Prepared(
                        replay.getId(), "REPLAYED",
                        replay.getDocumentRevision(),
                        latestVersion(replay), false, false, false,
                        null, policy);
            }

            RagDocument duplicate = findDuplicate(
                    request.getDeduplicationScope(),
                    DigestUtils.sha256(request.getContent()),
                    collectionId);
            if (duplicate != null) {
                EmbeddingDispatchService.Result duplicateDispatch =
                        force && policy != EmbeddingPolicy.SKIP
                                ? dispatch(
                                        duplicate, false, policy, true,
                                        origin + "_DUPLICATE_FORCE")
                                : null;
                completeIdempotency(reservation, duplicate.getId());
                return new Prepared(
                        duplicate.getId(), "DUPLICATE",
                        duplicate.getDocumentRevision() == null
                                ? 1L : duplicate.getDocumentRevision(),
                        latestVersion(duplicate), false, false, false,
                        duplicateDispatch, policy);
            }

            RagDocument document = new RagDocument();
            document.setTitle(request.getTitle().trim());
            document.setContent(request.getContent());
            document.setSource(normalizeOptional(request.getSource()));
            document.setDocumentType(normalizeDocumentType(request.getDocumentType()));
            document.setMetadata(normalizeMetadata(request.getMetadata()));
            document.setContentHash(DigestUtils.sha256(request.getContent()));
            document.setCollectionId(collectionId);
            document.setOriginalFilename(normalizeOptional(originalFilename));
            document.setJsonbPayload(jsonbPayload == null
                    ? null : jsonbPayload.deepCopy());
            document.setSourceNamespace("default");
            document.setDocumentRevision(1L);
            document.setNextHistoryVersion(1);
            document.setSize(byteSize(request.getContent()));
            document.setEnabled(enabledOverride == null
                    || Boolean.TRUE.equals(enabledOverride));
            document.setProcessingStatus("PENDING");
            document = documentRepository.saveAndFlush(document);
            RagDocumentVersion version = versionService.forceRecordVersion(
                    document, "CREATE", "Local document created");
            EmbeddingDispatchService.Result dispatch = Boolean.TRUE.equals(
                    document.getEnabled())
                    ? dispatch(document, true, policy, force, origin)
                    : dispatch(
                            document, true, EmbeddingPolicy.SKIP,
                            false, origin);
            completeIdempotency(reservation, document.getId());
            return new Prepared(
                    document.getId(), "CREATED", document.getDocumentRevision(),
                    version.getVersionNumber(), true, true,
                    collectionId != null, dispatch, policy);
        }));
        return new CreatedLocal(
                documentRepository.findById(prepared.documentId())
                        .orElseThrow(() -> new DocumentNotFoundException(
                                prepared.documentId())),
                finish(prepared));
    }

    public CreatedLocal upsertLocalImport(
            Long existingDocumentId,
            DocumentRequest request,
            Long collectionId,
            String originalFilename,
            JsonNode jsonbPayload,
            Boolean enabledOverride,
            EmbeddingPolicy requestedPolicy,
            boolean force,
            String origin) {
        if (existingDocumentId == null) {
            request.setDeduplicationScope(DocumentDeduplicationScope.NONE);
            return createLocal(
                    request, collectionId, requestedPolicy, force, origin,
                    null, originalFilename, jsonbPayload, enabledOverride);
        }
        EmbeddingPolicy policy = requestedPolicy == null
                ? EmbeddingPolicy.SKIP : requestedPolicy;
        validateCreate(request);
        Prepared prepared = requireResult(transactionTemplate.execute(status -> {
            RagDocument document = requireLocal(existingDocumentId);
            reserveCollections(document.getCollectionId(), collectionId);
            String title = request.getTitle().trim();
            String content = request.getContent();
            String source = normalizeOptional(request.getSource());
            String documentType = normalizeDocumentType(
                    request.getDocumentType());
            Map<String, Object> metadata = normalizeMetadata(
                    request.getMetadata());
            String filename = normalizeOptional(originalFilename);
            JsonNode payload = jsonbPayload == null
                    ? null : jsonbPayload.deepCopy();
            boolean enabled = enabledOverride == null
                    ? Boolean.TRUE.equals(document.getEnabled())
                    : Boolean.TRUE.equals(enabledOverride);
            String contentHash = DigestUtils.sha256(content);
            boolean contentChanged = !Objects.equals(
                    document.getContentHash(), contentHash)
                    || !Objects.equals(
                            normalizeDocumentKind(document.getDocumentType()),
                            normalizeDocumentKind(documentType));
            boolean metadataChanged =
                    !Objects.equals(document.getTitle(), title)
                    || !Objects.equals(document.getSource(), source)
                    || !Objects.equals(
                            normalizeMetadata(document.getMetadata()), metadata)
                    || !Objects.equals(
                            document.getOriginalFilename(), filename)
                    || !Objects.equals(document.getJsonbPayload(), payload)
                    || !Objects.equals(
                            Boolean.TRUE.equals(document.getEnabled()), enabled);
            boolean scopeChanged = !Objects.equals(
                    document.getCollectionId(), collectionId);
            if (!contentChanged && !metadataChanged && !scopeChanged && !force) {
                return new Prepared(
                        document.getId(), "UNCHANGED",
                        document.getDocumentRevision(),
                        latestVersion(document), false, false, false,
                        null, policy);
            }
            document.setTitle(title);
            document.setContent(content);
            document.setSource(source);
            document.setDocumentType(documentType);
            document.setMetadata(metadata);
            document.setOriginalFilename(filename);
            document.setJsonbPayload(payload);
            document.setCollectionId(collectionId);
            document.setContentHash(contentHash);
            document.setSize(byteSize(content));
            document.setEnabled(enabled);
            document.setDisabledAt(enabled ? null : LocalDateTime.now());
            if (contentChanged) {
                document.setProcessingStatus("PENDING");
                document.setProcessingError(null);
            }
            incrementRevision(document);
            document = documentRepository.saveAndFlush(document);
            RagDocumentVersion version = versionService.forceRecordVersion(
                    document,
                    scopeChanged ? "COLLECTION_MOVE" : "UPDATE",
                    "Local import synchronized");
            ensureLocalForNonSkipMutation(document, policy, contentChanged);
            boolean needsDerivation = enabled && (contentChanged || force
                    || !documentEmbedService.hasFreshEmbedding(document));
            EmbeddingDispatchService.Result dispatch = needsDerivation
                    ? dispatch(document, contentChanged, policy, force, origin)
                    : !enabled && contentChanged
                            ? dispatch(
                                    document, true, EmbeddingPolicy.SKIP,
                                    false, origin)
                            : null;
            return new Prepared(
                    document.getId(), "UPDATED",
                    document.getDocumentRevision(),
                    version.getVersionNumber(), contentChanged,
                    metadataChanged, scopeChanged, dispatch, policy);
        }));
        RagDocument document = documentRepository.findById(prepared.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(
                        prepared.documentId()));
        return new CreatedLocal(document, finish(prepared));
    }

    public DocumentMutationResponse updateLocal(
            long documentId,
            DocumentUpdateRequest request) {
        validateUpdateRequest(request);
        Prepared prepared = requireResult(transactionTemplate.execute(status -> {
            RagDocument document = requireLocal(documentId);
            requireRevision(document, request.getExpectedDocumentRevision());

            String title = request.isTitlePresent()
                    ? requireText(request.getTitle(), "title", 255)
                    : document.getTitle();
            String content = request.isContentPresent()
                    ? requireContent(
                            request.getContent(), "content", 1_000_000)
                    : document.getContent();
            String source = request.isSourcePresent()
                    ? normalizeOptional(request.getSource())
                    : document.getSource();
            Map<String, Object> metadata = request.isMetadataPresent()
                    ? normalizeMetadata(request.getMetadata())
                    : normalizeMetadata(document.getMetadata());
            Long collectionId = request.isCollectionKeyPresent()
                    ? resolveUpdateCollection(document, request.getCollectionKey())
                    : document.getCollectionId();
            reserveCollections(document.getCollectionId(), collectionId);

            boolean contentChanged = !Objects.equals(document.getContent(), content);
            boolean metadataChanged = !Objects.equals(
                    normalizeMetadata(document.getMetadata()), metadata)
                    || !Objects.equals(document.getTitle(), title)
                    || !Objects.equals(document.getSource(), source);
            boolean scopeChanged = !Objects.equals(
                    document.getCollectionId(), collectionId);
            if (!contentChanged && !metadataChanged && !scopeChanged) {
                return new Prepared(
                        document.getId(), "UNCHANGED",
                        document.getDocumentRevision(),
                        latestVersion(document),
                        false, false, false, null,
                        request.getEmbeddingPolicy());
            }
            if (!Boolean.TRUE.equals(document.getEnabled())
                    && contentChanged
                    && request.getEmbeddingPolicy() != EmbeddingPolicy.SKIP) {
                throw new RagException(
                        ErrorCode.DOCUMENT_DISABLED,
                        "Disabled documents can only change content with embeddingPolicy=SKIP");
            }

            document.setTitle(title);
            document.setContent(content);
            document.setSource(source);
            document.setMetadata(metadata);
            document.setCollectionId(collectionId);
            if (contentChanged) {
                document.setContentHash(DigestUtils.sha256(content));
                document.setSize(byteSize(content));
                document.setProcessingStatus("PENDING");
                document.setProcessingError(null);
            }
            incrementRevision(document);
            document = documentRepository.saveAndFlush(document);
            RagDocumentVersion version = versionService.forceRecordVersion(
                    document,
                    scopeChanged ? "COLLECTION_MOVE" : "UPDATE",
                    contentChanged
                            ? "Local document content updated"
                            : "Local document metadata or scope updated");
            ensureLocalForNonSkipMutation(
                    document, request.getEmbeddingPolicy(), contentChanged);
            EmbeddingDispatchService.Result dispatch = contentChanged
                    ? dispatch(
                            document, true, request.getEmbeddingPolicy(),
                            false, "LOCAL_PATCH")
                    : null;
            return new Prepared(
                    document.getId(), "UPDATED",
                    document.getDocumentRevision(),
                    version.getVersionNumber(),
                    contentChanged, metadataChanged, scopeChanged,
                    dispatch, request.getEmbeddingPolicy());
        }));
        return finish(prepared);
    }

    public DocumentMutationResponse disableLocal(
            long documentId,
            DocumentDisableRequest request) {
        rejectUnknown(request.getUnknownFieldNames());
        Prepared prepared = requireResult(transactionTemplate.execute(status -> {
            RagDocument document = requireLocal(documentId);
            requireRevision(document, request.getExpectedDocumentRevision());
            reserveCollections(document.getCollectionId());
            if (!Boolean.TRUE.equals(document.getEnabled())) {
                return new Prepared(
                        document.getId(), "UNCHANGED",
                        document.getDocumentRevision(),
                        latestVersion(document), false, false, false,
                        null, EmbeddingPolicy.SKIP);
            }
            document.setEnabled(false);
            document.setDisabledAt(LocalDateTime.now());
            incrementRevision(document);
            document = documentRepository.saveAndFlush(document);
            RagDocumentVersion version = versionService.forceRecordVersion(
                    document, "DISABLE", "Local document disabled");
            dispatchService.markNotRequestedInCurrentTransaction(document);
            dispatchService.cancelActiveInCurrentTransaction(document.getId());
            return new Prepared(
                    document.getId(), "DISABLED",
                    document.getDocumentRevision(),
                    version.getVersionNumber(), false, false, false,
                    null, EmbeddingPolicy.SKIP);
        }));
        return finish(prepared);
    }

    public DocumentMutationResponse restoreLocal(
            long documentId,
            DocumentRestoreRequest request) {
        rejectUnknown(request.getUnknownFieldNames());
        Prepared prepared = requireResult(transactionTemplate.execute(status -> {
            RagDocument document = requireLocal(documentId);
            requireRevision(document, request.getExpectedDocumentRevision());
            reserveCollections(document.getCollectionId());
            if (Boolean.TRUE.equals(document.getEnabled())) {
                return new Prepared(
                        document.getId(), "UNCHANGED",
                        document.getDocumentRevision(),
                        latestVersion(document), false, false, false,
                        null, request.getEmbeddingPolicy());
            }
            document.setEnabled(true);
            document.setDisabledAt(null);
            incrementRevision(document);
            document = documentRepository.saveAndFlush(document);
            RagDocumentVersion version = versionService.forceRecordVersion(
                    document, "RESTORE", "Local document restored");
            ensureLocalForNonSkipMutation(
                    document, request.getEmbeddingPolicy(), false);
            EmbeddingDispatchService.Result dispatch =
                    documentEmbedService.hasFreshEmbedding(document)
                            ? null
                            : dispatch(
                                    document, true,
                                    request.getEmbeddingPolicy(),
                                    false, "LOCAL_RESTORE");
            return new Prepared(
                    document.getId(), "RESTORED",
                    document.getDocumentRevision(),
                    version.getVersionNumber(), false, false, false,
                    dispatch, request.getEmbeddingPolicy());
        }));
        return finish(prepared);
    }

    /**
     * Restore a complete local version as a new mutation.
     *
     * <p>The historical row is immutable. The current document receives a
     * new business revision and a new RESTORE snapshot, so embedding jobs
     * continue to use the normal generation and lease fencing.
     */
    public DocumentMutationResponse restoreLocalFromVersion(
            long documentId,
            int versionNumber,
            DocumentVersionRestoreRequest request) {
        if (!properties.isVersionRestoreEnabled()) {
            throw new RagException(
                    ErrorCode.RESTORE_NOT_ALLOWED,
                    "Version restore is disabled");
        }
        Objects.requireNonNull(request, "request");
        EmbeddingPolicy policy = request.effectiveEmbeddingPolicy();
        DocumentRestoreVisibilityMode visibilityMode =
                request.effectiveVisibilityMode();
        Prepared prepared = requireResult(transactionTemplate.execute(status -> {
            RagDocument document = requireLocal(documentId);
            requireRevision(document, request.expectedDocumentRevision());
            RagDocumentVersion version = versionService.getVersion(
                            documentId, versionNumber)
                    .orElseThrow(() -> new RagException(
                            ErrorCode.VERSION_NOT_RESTORABLE,
                            "Document version not found"));
            if (!"FULL".equals(version.getSnapshotCompleteness())) {
                throw new RagException(
                        ErrorCode.VERSION_NOT_RESTORABLE,
                        "Only FULL document snapshots can be restored");
            }
            if (version.getContentSnapshot() == null
                    || version.getContentHash() == null) {
                throw new RagException(
                        ErrorCode.VERSION_NOT_RESTORABLE,
                        "The selected version does not contain a complete content snapshot");
            }

            Long targetCollectionId = version.getCollectionIdSnapshot();
            if (targetCollectionId != null) {
                ApiKeyCollectionAccess.requireCollectionId(
                        targetCollectionId,
                        ApiKeyCollectionAccess.currentPolicy());
                collectionIdentityResolver.requireActive(
                        targetCollectionId, null);
            } else if (ApiKeyCollectionAccess.restrictedCollectionIds(
                    ApiKeyCollectionAccess.currentPolicy()).isPresent()
                    && document.getCollectionId() != null) {
                throw new RagException(
                        ErrorCode.RESTORE_NOT_ALLOWED,
                        "A Collection-restricted key cannot restore an unassigned snapshot");
            }
            reserveCollections(document.getCollectionId(), targetCollectionId);

            String content = version.getContentSnapshot();
            String documentType = normalizeDocumentType(
                    version.getDocumentTypeSnapshot());
            Map<String, Object> metadata = normalizeMetadata(
                    version.getMetadataSnapshot());
            JsonNode payload = version.getJsonbPayloadSnapshot() == null
                    ? null : version.getJsonbPayloadSnapshot().deepCopy();
            boolean contentChanged = !Objects.equals(
                    document.getContent(), content)
                    || !Objects.equals(
                            normalizeDocumentKind(document.getDocumentType()),
                            normalizeDocumentKind(documentType));
            boolean metadataChanged = !Objects.equals(
                    document.getTitle(), version.getTitleSnapshot())
                    || !Objects.equals(document.getSource(), version.getSourceSnapshot())
                    || !Objects.equals(
                            document.getDocumentType(), documentType)
                    || !Objects.equals(
                            document.getOriginalFilename(),
                            version.getOriginalFilenameSnapshot())
                    || !Objects.equals(
                            normalizeMetadata(document.getMetadata()), metadata)
                    || !Objects.equals(document.getJsonbPayload(), payload);
            boolean scopeChanged = !Objects.equals(
                    document.getCollectionId(), targetCollectionId);
            boolean visibilityChanged = visibilityMode
                    == DocumentRestoreVisibilityMode.SNAPSHOT
                    && !Objects.equals(
                            document.getEnabled(), version.getEnabledSnapshot());

            document.setTitle(requireText(
                    version.getTitleSnapshot(), "title", 255));
            document.setContent(content);
            document.setSource(normalizeOptional(version.getSourceSnapshot()));
            document.setDocumentType(documentType);
            document.setOriginalFilename(normalizeOptional(
                    version.getOriginalFilenameSnapshot()));
            document.setMetadata(metadata);
            document.setJsonbPayload(payload);
            document.setCollectionId(targetCollectionId);
            document.setContentHash(version.getContentHash());
            document.setSize(byteSize(content));
            if (visibilityMode == DocumentRestoreVisibilityMode.SNAPSHOT) {
                boolean enabled = Boolean.TRUE.equals(version.getEnabledSnapshot());
                document.setEnabled(enabled);
                document.setDisabledAt(enabled
                        ? null : version.getDisabledAtSnapshot());
            }
            if (contentChanged) {
                document.setProcessingStatus("PENDING");
                document.setProcessingError(null);
            }
            incrementRevision(document);
            document = documentRepository.saveAndFlush(document);
            RagDocumentVersion restored = versionService.forceRecordVersion(
                    document,
                    "RESTORE",
                    "Restored local document from version " + versionNumber);
            EmbeddingDispatchService.Result dispatch;
            if (!Boolean.TRUE.equals(document.getEnabled())) {
                dispatch = dispatch(
                        document, true, EmbeddingPolicy.SKIP,
                        false, "LOCAL_VERSION_RESTORE");
            } else if (contentChanged
                    || !documentEmbedService.hasFreshEmbedding(document)) {
                dispatch = dispatch(
                        document, contentChanged, policy,
                        false, "LOCAL_VERSION_RESTORE");
            } else {
                dispatch = null;
            }
            return new Prepared(
                    document.getId(),
                    "RESTORED_VERSION",
                    document.getDocumentRevision(),
                    restored.getVersionNumber(),
                    contentChanged,
                    metadataChanged || visibilityChanged,
                    scopeChanged,
                    dispatch,
                    policy);
        }));
        return finish(prepared);
    }

    public DeletedLocal hardDeleteLocal(
            long documentId,
            long expectedRevision) {
        return requireResult(transactionTemplate.execute(status -> {
            RagDocument document = requireLocal(documentId);
            requireRevision(document, expectedRevision);
            reserveCollections(document.getCollectionId());
            long embeddings = embeddingRepository.countByDocumentId(documentId);
            dispatchService.cancelActiveInCurrentTransaction(documentId);
            embeddingRepository.deleteByDocumentId(documentId);
            documentRepository.delete(document);
            documentRepository.flush();
            return new DeletedLocal(
                    documentId, expectedRevision, embeddings);
        }));
    }

    public ExternalDocumentUpsertResponse upsertExternal(
            ExternalDocumentUpsertRequest request) {
        Objects.requireNonNull(request, "request");
        String collectionKey = requireText(
                request.getCollectionKey(), "collectionKey", 128);
        RagCollection collection = resolveExternalCollection(collectionKey);
        String namespace = normalizeNamespace(request.getSourceNamespace());
        String externalId = requireText(
                request.getExternalId(), "externalId", 255);
        String sourceRevision = requireText(
                request.getSourceRevision(), "sourceRevision", 255);
        EmbeddingPolicy policy = EmbeddingPolicyResolver.resolve(
                request.getEmbeddingPolicy(), request.isEmbed());
        ExternalPrepared prepared = executeExternalInTransaction(false, () ->
                upsertExternalInTransaction(
                        collection.getId(),
                        collectionKey,
                        namespace,
                        externalId,
                        sourceRevision,
                        normalizeOptional(request.getExpectedSourceRevision()),
                        requireText(request.getTitle(), "title", 255),
                        requireContent(
                                request.getContent(), "content", 1_000_000),
                        normalizeOptional(request.getSource()),
                        normalizeDocumentType(request.getDocumentType()),
                        normalizeMetadata(request.getMetadata()),
                        null,
                        false,
                        policy,
                        "EXTERNAL_UPSERT"));
        ExternalFinished finished = finishExternal(prepared);
        RagDocument document = finished.document();
        return new ExternalDocumentUpsertResponse(
                document.getId(),
                collectionKey,
                externalId,
                document.getSourceRevision(),
                prepared.action(),
                prepared.contentChanged(),
                prepared.versionNumber(),
                finished.lifecycle().embeddingStatus(),
                finished.lifecycle().activeEmbeddingProfileKey(),
                "READY".equals(finished.lifecycle().searchability()),
                document.getProcessingStatus(),
                document.getSourceDeletedAt(),
                null,
                finished.dispatch() == null
                        ? null : finished.dispatch().error(),
                finished.dispatch() == null
                        ? "NONE" : finished.dispatch().action().name(),
                finished.dispatch() == null
                        ? null : finished.dispatch().embeddingJobId(),
                finished.dispatch() == null
                        ? null : finished.dispatch().embeddingBatchId(),
                namespace,
                document.getDocumentRevision(),
                finished.lifecycle());
    }

    public JsonMutationResult upsertJsonRecord(
            JsonRecordUpsertRequest request,
            Long collectionId,
            String collectionKey,
            String originalFilename,
            Boolean enabledOverride) {
        Objects.requireNonNull(request, "request");
        String namespace = normalizeNamespace(request.getSourceNamespace());
        String externalId = requireText(
                request.getExternalId(), "externalId", 255);
        String sourceRevision = normalizeOptional(request.getSourceRevision());
        EmbeddingPolicy policy = EmbeddingPolicyResolver.resolve(
                request.getEmbeddingPolicy(), request.isEmbed());
        JsonNode payload = Objects.requireNonNull(
                request.getJsonbPayload(), "jsonbPayload").deepCopy();
        if (payload.isNull()) {
            throw new IllegalArgumentException(
                    "jsonbPayload must not be JSON null");
        }
        ExternalPrepared prepared = executeExternalInTransaction(true, () ->
                upsertExternalInTransaction(
                        collectionId,
                        collectionKey,
                        namespace,
                        externalId,
                        sourceRevision,
                        normalizeOptional(request.getExpectedSourceRevision()),
                        requireText(request.getTitle(), "title", 255),
                        requireContent(
                                request.getRetrievalText(),
                                "retrievalText",
                                1_000_000),
                        normalizeOptional(request.getSource()),
                        RagDocument.JSON_RECORD,
                        normalizeMetadata(request.getMetadata()),
                        payload,
                        true,
                        policy,
                        "JSON_RECORD_UPSERT",
                        originalFilename,
                        enabledOverride,
                        null,
                        null,
                        false));
        ExternalFinished finished = finishExternal(prepared);
        return new JsonMutationResult(
                finished.document(),
                prepared.action(),
                prepared.contentChanged(),
                prepared.payloadChanged(),
                prepared.versionNumber(),
                finished.dispatch(),
                finished.lifecycle());
    }

    /**
     * Apply one item from an authoritative snapshot through the same mutation
     * path as the ordinary external document APIs.
     *
     * <p>The caller owns the sync-run ledger transaction. This method joins
     * that transaction when one exists, so a document mutation and its
     * last-seen marker cannot commit independently.
     */
    public SyncItemMutation upsertSyncRunItem(
            Long collectionId,
            String collectionKey,
            String namespace,
            DocumentSyncRunItemRequest request,
            long snapshotStartSequence) {
        Objects.requireNonNull(request, "request");
        return requireResult(transactionTemplate.execute(status ->
                upsertSyncRunItemInCurrentTransaction(
                        collectionId,
                        collectionKey,
                        namespace,
                        request,
                        snapshotStartSequence)));
    }

    /**
     * Variant for a caller that already owns the transaction containing the
     * sync-run ledger row.
     */
    SyncItemMutation upsertSyncRunItemInCurrentTransaction(
            Long collectionId,
            String collectionKey,
            String namespace,
            DocumentSyncRunItemRequest request,
            long snapshotStartSequence) {
        Objects.requireNonNull(request, "request");
            boolean jsonRecord = request.documentKind()
                    == DocumentSyncDocumentKind.JSON_RECORD;
            String externalId = requireText(
                    request.externalId(), "externalId", 255);
            String sourceRevision = requireText(
                    request.sourceRevision(), "sourceRevision", 255);
            RagDocument current = documentRepository
                    .findByCollectionIdAndSourceNamespaceAndExternalId(
                            collectionId, namespace, externalId)
                    .orElse(null);
            if (current != null
                    && current.getSourceMutationSequence() != null
                    && current.getSourceMutationSequence()
                            > snapshotStartSequence) {
                return new SyncItemMutation(
                        DocumentSyncItemStatus.SKIPPED_NEWER_MUTATION,
                        current.getId(),
                        current.getSourceRevision(),
                        "NONE",
                        null,
                        null,
                        null);
            }

            EmbeddingPolicy policy = request.effectiveEmbeddingPolicy();
            String title = requireText(request.title(), "title", 255);
            String content = jsonRecord
                    ? requireContent(
                            request.retrievalText(),
                            "retrievalText",
                            1_000_000)
                    : requireContent(
                            request.content(), "content", 1_000_000);
            String documentType = jsonRecord
                    ? RagDocument.JSON_RECORD
                    : normalizeDocumentType(request.documentType());
            JsonNode payload = jsonRecord
                    ? Objects.requireNonNull(
                            request.jsonbPayload(),
                            "jsonbPayload must not be null").deepCopy()
                    : null;
            if (jsonRecord && payload.isNull()) {
                throw new IllegalArgumentException(
                        "jsonbPayload must not be JSON null");
            }
            ExternalPrepared prepared = upsertExternalInTransaction(
                    collectionId,
                    collectionKey,
                    namespace,
                    externalId,
                    sourceRevision,
                    current == null
                            ? null : normalizeOptional(current.getSourceRevision()),
                    title,
                    content,
                    normalizeOptional(request.source()),
                    documentType,
                    normalizeMetadata(request.metadata()),
                    payload,
                    jsonRecord,
                    policy,
                    "SYNC_RUN_ITEM",
                    null,
                    null,
                    null,
                    snapshotStartSequence,
                    true);
            RagDocument document = documentRepository.findById(prepared.documentId())
                    .orElseThrow(() -> new DocumentNotFoundException(
                            prepared.documentId()));
            return new SyncItemMutation(
                    "UNCHANGED".equals(prepared.action())
                            ? DocumentSyncItemStatus.UNCHANGED
                            : DocumentSyncItemStatus.APPLIED,
                    document.getId(),
                    document.getSourceRevision(),
                    prepared.dispatch() == null
                            ? "NONE" : prepared.dispatch().action().name(),
                    prepared.dispatch() == null
                            ? null : prepared.dispatch().error(),
                    prepared.dispatch() == null
                            ? null : prepared.dispatch().embeddingJobId(),
                    null);
    }

    /**
     * Mark an external document missing in a completed authoritative snapshot
     * without changing its source-owned revision.
     */
    public boolean reconcileMissingExternal(
            long documentId,
            UUID runId,
            long snapshotStartSequence) {
        return requireResult(transactionTemplate.execute(status -> {
            RagDocument document = documentRepository.findById(documentId)
                    .orElse(null);
            if (document == null
                    || document.getExternalId() == null
                    || document.getSourceRevision() == null
                    || Boolean.FALSE.equals(document.getEnabled())
                    || (document.getSourceMutationSequence() != null
                        && document.getSourceMutationSequence()
                                > snapshotStartSequence)) {
                return false;
            }
            reserveCollections(document.getCollectionId());
            document.setEnabled(false);
            document.setDisabledAt(null);
            document.setSourceDeletedAt(LocalDateTime.now());
            document.setDeletionOrigin("RECONCILIATION");
            document.setReconciliationTombstoneRunId(runId);
            incrementRevision(document);
            document = documentRepository.saveAndFlush(document);
            versionService.forceRecordVersion(
                    document,
                    "TOMBSTONE",
                    "Authoritative source snapshot marked document missing");
            dispatchService.markNotRequestedInCurrentTransaction(document);
            dispatchService.cancelActiveInCurrentTransaction(documentId);
            return true;
        }));
    }

    /**
     * Allocate a namespace sequence for a snapshot boundary. Conditional DML
     * is the cross-instance coordination primitive; no row lock is used.
     */
    public long allocateSourceSequenceForSnapshot(
            long collectionId,
            String namespace) {
        return allocateSourceSequence(collectionId, namespace);
    }

    public ExternalDocumentDeleteResponse tombstoneExternal(
            String collectionKey,
            String sourceNamespace,
            String externalId,
            String sourceRevision,
            String expectedSourceRevision,
            boolean jsonRecord) {
        String normalizedKey = requireText(
                collectionKey, "collectionKey", 128);
        RagCollection collection = resolveExternalCollection(normalizedKey);
        String namespace = normalizeNamespace(sourceNamespace);
        String normalizedExternalId = requireText(
                externalId, "externalId", 255);
        String revision = requireText(
                sourceRevision, "sourceRevision", 255);
        ExternalPrepared prepared = executeExternalInTransaction(jsonRecord, () -> {
            reserveCollections(collection.getId());
            long mutationSequence = allocateSourceSequence(
                    collection.getId(), namespace);
            requireAddressNotRetired(
                    collection.getId(), namespace, normalizedExternalId);
            RagDocument document = documentRepository
                    .findByCollectionIdAndSourceNamespaceAndExternalId(
                            collection.getId(), namespace, normalizedExternalId)
                    .orElseThrow(() -> new DocumentNotFoundException(-1L));
            requireKind(document, jsonRecord);
            String currentRevision = normalizeOptional(
                    document.getSourceRevision());
            boolean tombstoned = !Boolean.TRUE.equals(document.getEnabled())
                    && document.getSourceDeletedAt() != null;
            if (tombstoned && Objects.equals(revision, currentRevision)) {
                return new ExternalPrepared(
                        document.getId(), "UNCHANGED",
                        document.getDocumentRevision(),
                        latestVersion(document), false, false,
                        null, EmbeddingPolicy.SKIP);
            }
            if (Objects.equals(revision, currentRevision)) {
                throw revisionConflict(
                        jsonRecord,
                        "A source deletion must use a new sourceRevision");
            }
            requireExpectedSourceRevision(
                    jsonRecord,
                    currentRevision,
                    normalizeOptional(expectedSourceRevision));
            document.setEnabled(false);
            document.setDisabledAt(null);
            document.setSourceDeletedAt(LocalDateTime.now());
            document.setSourceRevision(revision);
            document.setSourceMutationSequence(mutationSequence);
            document.setDeletionOrigin("SOURCE");
            document.setReconciliationTombstoneRunId(null);
            incrementRevision(document);
            document = documentRepository.saveAndFlush(document);
            RagDocumentVersion version = versionService.forceRecordVersion(
                    document, "TOMBSTONE",
                    "External source tombstoned the document");
            dispatchService.markNotRequestedInCurrentTransaction(document);
            dispatchService.cancelActiveInCurrentTransaction(document.getId());
            return new ExternalPrepared(
                    document.getId(), "DELETED",
                    document.getDocumentRevision(),
                    version.getVersionNumber(), false, false,
                    null, EmbeddingPolicy.SKIP);
        });
        RagDocument document = documentRepository.findById(prepared.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(
                        prepared.documentId()));
        DocumentLifecycleResponse lifecycle = lifecycleService.read(document);
        return new ExternalDocumentDeleteResponse(
                document.getId(),
                normalizedKey,
                normalizedExternalId,
                document.getSourceRevision(),
                prepared.action(),
                prepared.versionNumber(),
                Boolean.TRUE.equals(document.getEnabled()),
                document.getSourceDeletedAt(),
                null,
                null,
                namespace,
                document.getDocumentRevision(),
                lifecycle);
    }

    private ExternalPrepared upsertExternalInTransaction(
            Long collectionId,
            String collectionKey,
            String namespace,
            String externalId,
            String sourceRevision,
            String expectedSourceRevision,
            String title,
            String content,
            String source,
            String documentType,
            Map<String, Object> metadata,
            JsonNode payload,
            boolean jsonRecord,
            EmbeddingPolicy policy,
            String origin) {
        return upsertExternalInTransaction(
                collectionId, collectionKey, namespace, externalId,
                sourceRevision, expectedSourceRevision, title, content,
                source, documentType, metadata, payload, jsonRecord,
                policy, origin, null, null, null, null, false);
    }

    private ExternalPrepared upsertExternalInTransaction(
            Long collectionId,
            String collectionKey,
            String namespace,
            String externalId,
            String sourceRevision,
            String expectedSourceRevision,
            String title,
            String content,
            String source,
            String documentType,
            Map<String, Object> metadata,
            JsonNode payload,
            boolean jsonRecord,
            EmbeddingPolicy policy,
            String origin,
            String originalFilename,
            Boolean enabledOverride,
            LocalDateTime sourceDeletedAtOverride,
            Long snapshotStartSequence,
            boolean allowReconciliationRecovery) {
        CollectionIdentityResolver.ActiveCollectionToken collectionToken =
                collectionIdentityResolver.beginActiveWrite(collectionId);
        requireAddressNotRetired(collectionId, namespace, externalId);
        String contentHash = DigestUtils.sha256(content);
        RagDocument document = documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        collectionId, namespace, externalId)
                .orElse(null);
        if (document != null) {
            requireKind(document, jsonRecord);
        }

        if (document == null) {
            if (expectedSourceRevision != null) {
                throw revisionConflict(
                        jsonRecord,
                        "expectedSourceRevision must be omitted for a new identity");
            }
            document = new RagDocument();
            document.setCollectionId(collectionId);
            document.setSourceNamespace(namespace);
            document.setExternalId(externalId);
            document.setDocumentRevision(1L);
            document.setNextHistoryVersion(1);
            document.setDocumentType(documentType);
            document.setEnabled(enabledOverride == null
                    || Boolean.TRUE.equals(enabledOverride));
        } else {
            String currentRevision = normalizeOptional(
                    document.getSourceRevision());
            if (sourceRevision != null
                    && Objects.equals(currentRevision, sourceRevision)) {
                if (sameExternalState(
                        document, title, contentHash, source,
                        documentType, metadata, payload)) {
                    confirmActiveCollectionWrite(collectionToken);
                    return new ExternalPrepared(
                            document.getId(), "UNCHANGED",
                            document.getDocumentRevision(),
                        latestVersion(document), false, false,
                        null, policy);
                }
                if (!(allowReconciliationRecovery
                        && "RECONCILIATION".equals(document.getDeletionOrigin())
                        && document.getSourceDeletedAt() != null
                        && sameExternalManagedState(
                                document, title, contentHash, source,
                                documentType, metadata, payload))) {
                    throw revisionConflict(
                            jsonRecord,
                            "The same sourceRevision was used for different managed fields");
                }
            }
            if (jsonRecord
                    && sourceRevision == null
                    && currentRevision == null
                    && sameExternalState(
                            document, title, contentHash, source,
                            documentType, metadata, payload)) {
                confirmActiveCollectionWrite(collectionToken);
                return new ExternalPrepared(
                        document.getId(), "UNCHANGED",
                        document.getDocumentRevision(),
                        latestVersion(document), false, false,
                        null, policy);
            }
            requireExpectedSourceRevision(
                    jsonRecord, currentRevision, expectedSourceRevision);
        }

        long mutationSequence = allocateSourceSequence(collectionId, namespace);
        requireAddressNotRetired(collectionId, namespace, externalId);
        boolean created = document.getId() == null;
        boolean contentChanged = created
                || !Objects.equals(document.getContentHash(), contentHash);
        boolean payloadChanged = !Objects.equals(
                document.getJsonbPayload(), payload);
        document.setTitle(title);
        document.setContent(content);
        document.setSource(source);
        document.setDocumentType(documentType);
        document.setMetadata(metadata);
        document.setJsonbPayload(payload);
        document.setContentHash(contentHash);
        document.setSize(byteSize(content));
        document.setCollectionId(collectionId);
        document.setSourceNamespace(namespace);
        document.setExternalId(externalId);
        document.setSourceRevision(sourceRevision);
        document.setSourceDeletedAt(sourceDeletedAtOverride);
        document.setDeletionOrigin(
                sourceDeletedAtOverride == null ? null : "SOURCE");
        document.setReconciliationTombstoneRunId(null);
        document.setDisabledAt(null);
        document.setEnabled(sourceDeletedAtOverride == null
                && (enabledOverride == null
                    || Boolean.TRUE.equals(enabledOverride)));
        if (originalFilename != null) {
            document.setOriginalFilename(originalFilename);
        }
        document.setSourceMutationSequence(mutationSequence);
        if (!created) {
            incrementRevision(document);
        }
        if (contentChanged) {
            document.setProcessingStatus("PENDING");
            document.setProcessingError(null);
        }
        document = documentRepository.saveAndFlush(document);
        RagDocumentVersion version = versionService.forceRecordVersion(
                document,
                created ? "CREATE" : "UPDATE",
                created
                        ? "External document created"
                        : "External managed state updated");
        ensureLocalForNonSkipMutation(document, policy, contentChanged);
        boolean needsDerivation = Boolean.TRUE.equals(document.getEnabled())
                && (contentChanged
                    || !documentEmbedService.hasFreshEmbedding(document));
        EmbeddingDispatchService.Result dispatch = needsDerivation
                ? dispatch(document, true, policy, false, origin)
                : null;
        confirmActiveCollectionWrite(collectionToken);
        return new ExternalPrepared(
                document.getId(),
                created ? "CREATED" : "UPDATED",
                document.getDocumentRevision(),
                version.getVersionNumber(),
                contentChanged,
                payloadChanged,
                dispatch,
                policy);
    }

    public void importDocument(
            long collectionId,
            String collectionKey,
            CollectionImportRequest.ImportedDocument imported) {
        Objects.requireNonNull(imported, "imported");
        String externalId = normalizeOptional(imported.getExternalId());
        if (externalId == null) {
            DocumentRequest request = new DocumentRequest(
                    imported.getTitle(), imported.getContent());
            request.setSource(imported.getSource());
            request.setDocumentType(imported.getDocumentType());
            request.setMetadata(imported.getMetadata());
            request.setDeduplicationScope(DocumentDeduplicationScope.NONE);
            createLocal(
                    request, collectionId, EmbeddingPolicy.SKIP,
                    false, "COLLECTION_IMPORT", null,
                    imported.getOriginalFilename(),
                    imported.getJsonbPayload(), imported.getEnabled());
            return;
        }

        boolean jsonRecord = RagDocument.JSON_RECORD.equals(
                imported.getDocumentType());
        String namespace = normalizeNamespace(imported.getSourceNamespace());
        JsonNode payload = imported.getJsonbPayload() == null
                ? null : imported.getJsonbPayload().deepCopy();
        if (jsonRecord && (payload == null || payload.isNull())) {
            throw new IllegalArgumentException(
                    "json-record import requires jsonbPayload");
        }
        ExternalPrepared prepared = requireResult(transactionTemplate.execute(status ->
                upsertExternalInTransaction(
                        collectionId,
                        collectionKey,
                        namespace,
                        externalId,
                        normalizeOptional(imported.getSourceRevision()),
                        null,
                        requireText(imported.getTitle(), "title", 255),
                        requireContent(
                                imported.getContent(), "content", 1_000_000),
                        normalizeOptional(imported.getSource()),
                        jsonRecord
                                ? RagDocument.JSON_RECORD
                                : normalizeDocumentType(
                                        imported.getDocumentType()),
                        normalizeMetadata(imported.getMetadata()),
                        payload,
                        jsonRecord,
                        EmbeddingPolicy.SKIP,
                        "COLLECTION_IMPORT",
                        imported.getOriginalFilename(),
                        imported.getEnabled(),
                        imported.getSourceDeletedAt(),
                        null,
                        false)));
        finishExternal(prepared);
    }

    public int unlinkLocalDocumentsFromCollection(long collectionId) {
        return requireResult(transactionTemplate.execute(status -> {
            List<RagDocument> documents =
                    documentRepository.findAllByCollectionId(collectionId);
            if (documents.stream().anyMatch(document ->
                    document.getExternalId() != null
                            && !document.getExternalId().isBlank())) {
                throw new DocumentRevisionConflictException(
                        "Collection contains external-managed documents; "
                                + "tombstone or purge them before deleting the Collection");
            }
            for (RagDocument document : documents) {
                document.setCollectionId(null);
                incrementRevision(document);
                RagDocument saved = documentRepository.saveAndFlush(document);
                versionService.forceRecordVersion(
                        saved, "COLLECTION_MOVE",
                        "Collection deleted; local document unassigned");
            }
            return documents.size();
        }));
    }

    private ExternalFinished finishExternal(ExternalPrepared prepared) {
        EmbeddingDispatchService.Result dispatch = prepared.dispatch();
        if (dispatch != null && prepared.policy() == EmbeddingPolicy.SYNC) {
            dispatch = dispatchService.completeAfterCommit(dispatch);
        }
        RagDocument document = documentRepository.findById(prepared.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(
                        prepared.documentId()));
        return new ExternalFinished(
                document, dispatch, lifecycleService.read(document));
    }

    private RagCollection resolveExternalCollection(String collectionKey) {
        return ApiKeyCollectionAccess.requireActiveCollectionByKey(
                collectionKey,
                ApiKeyCollectionAccess.currentPolicy(),
                collectionIdentityResolver);
    }

    private void confirmActiveCollectionWrite(
            CollectionIdentityResolver.ActiveCollectionToken token) {
        if (token != null) {
            collectionIdentityResolver.confirmActiveWrite(token);
        }
    }

    private void reserveCollections(Long... collectionIds) {
        collectionIdentityResolver.beginActiveWrites(
                java.util.Arrays.stream(collectionIds)
                        .filter(Objects::nonNull)
                        .toList());
    }

    private long allocateSourceSequence(
            long collectionId,
            String namespace) {
        jdbcTemplate.update("""
                INSERT INTO rag_document_source_namespaces (
                    collection_id, source_namespace
                ) VALUES (?, ?)
                ON CONFLICT (collection_id, source_namespace) DO NOTHING
                """,
                collectionId,
                namespace);
        Long sequence = jdbcTemplate.queryForObject("""
                UPDATE rag_document_source_namespaces
                SET mutation_sequence = mutation_sequence + 1,
                    row_version = row_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE collection_id = ?
                  AND source_namespace = ?
                RETURNING mutation_sequence
                """,
                Long.class,
                collectionId,
                namespace);
        if (sequence == null) {
            throw new IllegalStateException(
                    "Cannot allocate source mutation sequence");
        }
        return sequence;
    }

    private void requireAddressNotRetired(
            long collectionId,
            String namespace,
            String externalId) {
        if (addressRetirementService != null) {
            addressRetirementService.requireNotRetired(
                    collectionId, namespace, externalId);
        }
    }

    private void requireExpectedSourceRevision(
            boolean jsonRecord,
            String currentRevision,
            String expectedRevision) {
        if (currentRevision == null) {
            if (expectedRevision != null) {
                throw revisionConflict(
                        jsonRecord,
                        "Legacy identities must be claimed without expectedSourceRevision");
            }
            return;
        }
        if (expectedRevision == null && properties.isStrictExternalCas()) {
            throw revisionConflict(
                    jsonRecord,
                    "expectedSourceRevision is required for a new source revision");
        }
        if (expectedRevision != null
                && !Objects.equals(expectedRevision, currentRevision)) {
            throw revisionConflict(
                    jsonRecord,
                    "expectedSourceRevision does not match the current source revision");
        }
    }

    private RuntimeException revisionConflict(
            boolean jsonRecord,
            String message) {
        return jsonRecord
                ? new StructuredRecordConflictException(message)
                : new DocumentRevisionConflictException(message);
    }

    private <T> T executeExternalInTransaction(
            boolean jsonRecord,
            Supplier<T> callback) {
        RuntimeException lastFailure = null;
        for (int attempt = 1;
                attempt <= MAX_EXTERNAL_TRANSACTION_ATTEMPTS;
                attempt++) {
            try {
                return requireResult(transactionTemplate.execute(
                        status -> callback.get()));
            } catch (RuntimeException failure) {
                if (!isRetryableConcurrencyFailure(failure)) {
                    throw failure;
                }
                lastFailure = failure;
            }
        }
        String message = "Concurrent external document write did not converge after "
                + MAX_EXTERNAL_TRANSACTION_ATTEMPTS + " attempts";
        return throwExternalConflict(jsonRecord, message, lastFailure);
    }

    private boolean isRetryableConcurrencyFailure(RuntimeException failure) {
        return failure instanceof DataIntegrityViolationException
                || failure instanceof ConcurrencyFailureException;
    }

    private <T> T throwExternalConflict(
            boolean jsonRecord,
            String message,
            RuntimeException cause) {
        if (jsonRecord) {
            throw new StructuredRecordConflictException(message, cause);
        }
        throw new DocumentRevisionConflictException(message, cause);
    }

    private void requireKind(
            RagDocument document,
            boolean jsonRecord) {
        boolean actualJson = RagDocument.JSON_RECORD.equals(
                document.getDocumentType());
        if (actualJson != jsonRecord) {
            throw revisionConflict(
                    jsonRecord,
                    "External identity belongs to another document kind");
        }
    }

    private boolean sameExternalState(
            RagDocument document,
            String title,
            String contentHash,
            String source,
            String documentType,
            Map<String, Object> metadata,
            JsonNode payload) {
        return Boolean.TRUE.equals(document.getEnabled())
                && document.getSourceDeletedAt() == null
                && Objects.equals(document.getTitle(), title)
                && Objects.equals(document.getContentHash(), contentHash)
                && Objects.equals(document.getSource(), source)
                && Objects.equals(document.getDocumentType(), documentType)
                && Objects.equals(
                        normalizeMetadata(document.getMetadata()), metadata)
                && Objects.equals(document.getJsonbPayload(), payload);
    }

    private boolean sameExternalManagedState(
            RagDocument document,
            String title,
            String contentHash,
            String source,
            String documentType,
            Map<String, Object> metadata,
            JsonNode payload) {
        return Objects.equals(document.getTitle(), title)
                && Objects.equals(document.getContentHash(), contentHash)
                && Objects.equals(document.getSource(), source)
                && Objects.equals(document.getDocumentType(), documentType)
                && Objects.equals(
                        normalizeMetadata(document.getMetadata()), metadata)
                && Objects.equals(document.getJsonbPayload(), payload);
    }

    private String normalizeNamespace(String value) {
        String normalized = value == null || value.isBlank()
                ? "default" : value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException(
                    "sourceNamespace must not exceed 128 characters");
        }
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (current < 0x20 || current > 0x7e) {
                throw new IllegalArgumentException(
                        "sourceNamespace must contain visible ASCII only");
            }
        }
        if (!properties.isAllowNonDefaultNamespace()
                && !"default".equals(normalized)) {
            throw new IllegalArgumentException(
                    "Non-default sourceNamespace is disabled");
        }
        return normalized;
    }

    private RagDocument findDuplicate(
            DocumentDeduplicationScope scope,
            String contentHash,
            Long collectionId) {
        DocumentDeduplicationScope effective = scope == null
                ? DocumentDeduplicationScope.LEGACY_GLOBAL : scope;
        if (effective == DocumentDeduplicationScope.NONE) {
            return null;
        }
        return documentRepository.findByContentHash(contentHash).stream()
                .filter(document -> effective
                        != DocumentDeduplicationScope.COLLECTION
                        || Objects.equals(
                                document.getCollectionId(), collectionId))
                .filter(document -> {
                    try {
                        ApiKeyCollectionAccess.requireDocumentAccess(
                                document,
                                ApiKeyCollectionAccess.currentPolicy());
                        return true;
                    } catch (SecurityException ignored) {
                        return false;
                    }
                })
                .findFirst()
                .orElse(null);
    }

    private IdempotencyReservation reserveIdempotency(
            String rawKey,
            String operationType,
            String fingerprint) {
        if (rawKey == null || rawKey.isBlank()) {
            return IdempotencyReservation.none();
        }
        String normalized = rawKey.trim();
        if (normalized.length() > 255) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must not exceed 255 characters");
        }
        String owner = ChatPrincipal.fromCurrentRequest().id();
        String keyHash = DigestUtils.sha256(normalized);
        int inserted = jdbcTemplate.update("""
                INSERT INTO rag_document_idempotency_operations (
                    owner_principal_id, operation_type,
                    idempotency_key_hash, request_fingerprint,
                    status, expires_at
                ) VALUES (?, ?, ?, ?, 'IN_PROGRESS',
                    CURRENT_TIMESTAMP + (? * INTERVAL '1 hour'))
                ON CONFLICT (
                    owner_principal_id, operation_type, idempotency_key_hash
                ) DO NOTHING
                """,
                owner, operationType, keyHash, fingerprint,
                properties.getIdempotencyTtlHours());
        if (inserted == 1) {
            return new IdempotencyReservation(
                    owner, operationType, keyHash, null);
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT request_fingerprint, status, result_document_id,
                       expires_at <= CURRENT_TIMESTAMP AS expired
                FROM rag_document_idempotency_operations
                WHERE owner_principal_id = ?
                  AND operation_type = ?
                  AND idempotency_key_hash = ?
                """,
                owner, operationType, keyHash);
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "Idempotency operation disappeared during reservation");
        }
        Map<String, Object> row = rows.getFirst();
        if (Boolean.TRUE.equals(row.get("expired"))) {
            jdbcTemplate.update("""
                    DELETE FROM rag_document_idempotency_operations
                    WHERE owner_principal_id = ?
                      AND operation_type = ?
                      AND idempotency_key_hash = ?
                      AND expires_at <= CURRENT_TIMESTAMP
                    """, owner, operationType, keyHash);
            return reserveIdempotency(rawKey, operationType, fingerprint);
        }
        if (!Objects.equals(fingerprint, row.get("request_fingerprint"))) {
            throw new DocumentRevisionConflictException(
                    "Idempotency-Key was already used for another request");
        }
        Number result = (Number) row.get("result_document_id");
        if ("SUCCEEDED".equals(row.get("status")) && result != null) {
            return new IdempotencyReservation(
                    owner, operationType, keyHash, result.longValue());
        }
        throw new DocumentRevisionConflictException(
                "An operation with this Idempotency-Key is still in progress");
    }

    private void completeIdempotency(
            IdempotencyReservation reservation,
            long documentId) {
        if (reservation.owner() == null
                || reservation.replayDocumentId() != null) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE rag_document_idempotency_operations
                SET status = 'SUCCEEDED',
                    result_document_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE owner_principal_id = ?
                  AND operation_type = ?
                  AND idempotency_key_hash = ?
                """,
                documentId, reservation.owner(),
                reservation.operationType(), reservation.keyHash());
    }

    private String localCreateFingerprint(
            DocumentRequest request,
            Long collectionId,
            EmbeddingPolicy policy,
            boolean force,
            String originalFilename,
            JsonNode payload,
            Boolean enabledOverride) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("title", request.getTitle().trim());
        canonical.put("contentHash", DigestUtils.sha256(request.getContent()));
        canonical.put("source", normalizeOptional(request.getSource()));
        canonical.put("documentType",
                normalizeDocumentType(request.getDocumentType()));
        canonical.put("metadata",
                new TreeMap<>(normalizeMetadata(request.getMetadata())));
        canonical.put("collectionId", collectionId);
        canonical.put("embeddingPolicy", policy.name());
        canonical.put("deduplicationScope",
                request.getDeduplicationScope().name());
        canonical.put("force", force);
        canonical.put("originalFilename",
                normalizeOptional(originalFilename));
        canonical.put("jsonbPayload", payload);
        canonical.put("enabled", enabledOverride);
        try {
            return DigestUtils.sha256(
                    objectMapper.writeValueAsString(canonical));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Cannot canonicalize idempotent document request", e);
        }
    }

    private static String normalizeDocumentKind(String documentType) {
        return RagDocument.JSON_RECORD.equals(documentType)
                ? RagDocument.JSON_RECORD : "text";
    }

    private EmbeddingDispatchService.Result dispatch(
            RagDocument document,
            boolean contentChanged,
            EmbeddingPolicy policy,
            boolean force,
            String origin) {
        if (policy == EmbeddingPolicy.SKIP) {
            return dispatchService.markNotRequestedInCurrentTransaction(document);
        }
        return dispatchService.enqueueInCurrentTransaction(
                document, contentChanged, force, origin);
    }

    private void ensureLocalForNonSkipMutation(
            RagDocument document,
            EmbeddingPolicy policy,
            boolean contentChanged) {
        if (keywordIndexPersistenceService == null
                || !Boolean.TRUE.equals(document.getEnabled())
                || policy == EmbeddingPolicy.SKIP) {
            return;
        }
        keywordIndexPersistenceService.ensureCurrent(document);
    }

    private DocumentMutationResponse finish(Prepared prepared) {
        EmbeddingDispatchService.Result dispatch = prepared.dispatch();
        if (dispatch != null && prepared.policy() == EmbeddingPolicy.SYNC) {
            dispatch = dispatchService.completeAfterCommit(dispatch);
        }
        RagDocument document = documentRepository.findById(prepared.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(
                        prepared.documentId()));
        DocumentLifecycleResponse lifecycle = lifecycleService.read(document);
        return new DocumentMutationResponse(
                document.getId(),
                prepared.action(),
                prepared.documentRevision(),
                prepared.versionNumber(),
                prepared.contentChanged(),
                prepared.metadataChanged(),
                prepared.scopeChanged(),
                dispatch == null ? "NONE" : dispatch.action().name(),
                dispatch == null ? null : dispatch.embeddingJobId(),
                dispatch == null ? null : dispatch.embeddingBatchId(),
                lifecycle);
    }

    private RagDocument requireLocal(long documentId) {
        RagDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        ApiKeyCollectionAccess.requireDocumentAccess(
                document, ApiKeyCollectionAccess.currentPolicy());
        if (document.getExternalId() != null
                && !document.getExternalId().isBlank()) {
            throw new RagException(
                    ErrorCode.EXTERNAL_DOCUMENT_MANAGED,
                    "Externally managed documents must be changed by source identity");
        }
        return document;
    }

    private Long resolveUpdateCollection(
            RagDocument document,
            String collectionKey) {
        if (collectionKey == null) {
            if (ApiKeyCollectionAccess.restrictedCollectionIds(
                    ApiKeyCollectionAccess.currentPolicy()).isPresent()) {
                throw new SecurityException(
                        "A Collection-restricted key cannot unassign a document");
            }
            return null;
        }
        String normalized = requireText(
                collectionKey, "collectionKey", 128);
        RagCollection collection =
                ApiKeyCollectionAccess.requireActiveCollectionByKey(
                        normalized,
                        ApiKeyCollectionAccess.currentPolicy(),
                        collectionIdentityResolver);
        if (document.getCollectionId() != null) {
            ApiKeyCollectionAccess.requireCollectionId(
                    document.getCollectionId(),
                    ApiKeyCollectionAccess.currentPolicy());
        }
        return collection.getId();
    }

    private void validateCreate(DocumentRequest request) {
        Objects.requireNonNull(request, "request");
        requireText(request.getTitle(), "title", 255);
        requireContent(request.getContent(), "content", 1_000_000);
    }

    private void validateUpdateRequest(DocumentUpdateRequest request) {
        Objects.requireNonNull(request, "request");
        rejectUnknown(request.getUnknownFieldNames());
        if (!request.hasMutableFields()) {
            throw new RagException(
                    ErrorCode.EMPTY_PATCH,
                    "At least one mutable document field is required");
        }
    }

    private void rejectUnknown(java.util.Set<String> fields) {
        if (fields != null && !fields.isEmpty()) {
            throw new RagException(
                    ErrorCode.UNKNOWN_DOCUMENT_FIELD,
                    "Unknown document fields: " + String.join(", ", fields));
        }
    }

    private void requireRevision(
            RagDocument document,
            Long expectedRevision) {
        long current = document.getDocumentRevision() == null
                ? 1L : document.getDocumentRevision();
        if (expectedRevision == null || expectedRevision != current) {
            throw new DocumentRevisionConflictException(
                    "expectedDocumentRevision does not match current revision "
                            + current);
        }
    }

    private void incrementRevision(RagDocument document) {
        long current = document.getDocumentRevision() == null
                ? 1L : document.getDocumentRevision();
        document.setDocumentRevision(current + 1);
    }

    private int latestVersion(RagDocument document) {
        return versionService.getLatestVersion(document.getId())
                .map(RagDocumentVersion::getVersionNumber)
                .orElse(0);
    }

    private static String requireText(
            String value,
            String field,
            int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static String requireContent(
            String value,
            String field,
            int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
        return value;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeDocumentType(String value) {
        return value == null || value.isBlank() ? "text" : value.trim();
    }

    private static Map<String, Object> normalizeMetadata(
            Map<String, Object> value) {
        return value == null || value.isEmpty()
                ? Collections.emptyMap() : Map.copyOf(value);
    }

    private static long byteSize(String value) {
        return (long) value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static <T> T requireResult(T value) {
        return Objects.requireNonNull(
                value, "Document mutation transaction returned no result");
    }

    public record CreatedLocal(
            RagDocument document,
            DocumentMutationResponse mutation) {
    }

    public record DeletedLocal(
            long documentId,
            long documentRevision,
            long embeddingsRemoved) {
    }

    public record JsonMutationResult(
            RagDocument document,
            String action,
            boolean contentChanged,
            boolean payloadChanged,
            int versionNumber,
            EmbeddingDispatchService.Result dispatch,
            DocumentLifecycleResponse lifecycle) {
    }

    public record SyncItemMutation(
            DocumentSyncItemStatus status,
            Long documentId,
            String sourceRevision,
            String embeddingAction,
            String error,
            UUID embeddingJobId,
            String errorCode) {
    }

    private record Prepared(
            long documentId,
            String action,
            long documentRevision,
            int versionNumber,
            boolean contentChanged,
            boolean metadataChanged,
            boolean scopeChanged,
            EmbeddingDispatchService.Result dispatch,
            EmbeddingPolicy policy) {
    }

    private record ExternalPrepared(
            long documentId,
            String action,
            long documentRevision,
            int versionNumber,
            boolean contentChanged,
            boolean payloadChanged,
            EmbeddingDispatchService.Result dispatch,
            EmbeddingPolicy policy) {
    }

    private record ExternalFinished(
            RagDocument document,
            EmbeddingDispatchService.Result dispatch,
            DocumentLifecycleResponse lifecycle) {
    }

    private record IdempotencyReservation(
            String owner,
            String operationType,
            String keyHash,
            Long replayDocumentId) {

        private static IdempotencyReservation none() {
            return new IdempotencyReservation(null, null, null, null);
        }
    }
}
