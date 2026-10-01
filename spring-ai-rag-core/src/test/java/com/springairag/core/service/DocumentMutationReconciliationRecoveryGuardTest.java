package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.util.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对账恢复守卫的<b>拒绝侧</b>矩阵（Batch 770）。
 *
 * <p>Batch 397 只覆盖了恢复的<b>接受</b>侧：RECONCILIATION 墓碑 + 同
 * sourceRevision + 受管字段一致 → 复活成功。于是
 * {@code allowReconciliationRecovery && "RECONCILIATION".equals(origin)
 * && sourceDeletedAt != null && sameManagedFields(...)} 这条四段守卫的
 * <b>每一个否定分支</b>都没有任何测试走过——这正是"同 revision 但托管状态
 * 漂移"必须报冲突的那道闸门。
 *
 * <p>每个用例都让被测条件<b>之前</b>的各段成立、只让被测那一段不成立，
 * 否则短路求值会让测试根本没走到目标比较，白绿。
 */
class DocumentMutationReconciliationRecoveryGuardTest {

    private static final long COLLECTION_ID = 10L;
    private static final long DOCUMENT_ID = 41L;
    private static final String NAMESPACE = "cms";
    private static final String EXTERNAL_ID = "article-1";
    private static final String REVISION = "r1";
    private static final String BODY = "Body";

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver collectionResolver;
    private DocumentMutationService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        collectionResolver = mock(CollectionIdentityResolver.class);
        DocumentVersionService versionService =
                mock(DocumentVersionService.class);
        EmbeddingDispatchService dispatchService =
                mock(EmbeddingDispatchService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version =
                            new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(5);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(jdbcTemplate.update(anyString(), any(), any()))
                .thenReturn(1);
        // 恢复成功后要走更新路径，需要分配命名空间变更序列。
        lenient().when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), any(), any()))
                .thenReturn(9L);
        lenient().when(collectionResolver.beginActiveWrite(COLLECTION_ID))
                .thenReturn(new CollectionIdentityResolver
                        .ActiveCollectionToken(COLLECTION_ID, 1L));

        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                collectionResolver,
                versionService,
                dispatchService,
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);
    }

    // ─── 测试夹具 ─────────────────────────────────────────────────────

    /** 处于"对账墓碑"状态的文档：已禁用、带删除时间、删除来源为 RECONCILIATION。 */
    private RagDocument reconciliationTombstone() {
        RagDocument value = baseDocument();
        value.setEnabled(Boolean.FALSE);
        value.setSourceDeletedAt(LocalDateTime.now());
        value.setDeletionOrigin("RECONCILIATION");
        return value;
    }

    private RagDocument baseDocument() {
        RagDocument value = new RagDocument();
        value.setId(DOCUMENT_ID);
        value.setCollectionId(COLLECTION_ID);
        value.setSourceNamespace(NAMESPACE);
        value.setExternalId(EXTERNAL_ID);
        value.setSourceRevision(REVISION);
        value.setTitle("Article");
        value.setContent(BODY);
        value.setContentHash(DigestUtils.sha256(BODY));
        value.setSource("connector://x");
        value.setDocumentType("text");
        value.setMetadata(Map.of());
        value.setEnabled(Boolean.TRUE);
        value.setDocumentRevision(4L);
        return value;
    }

    private void stubExisting(RagDocument document) {
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, NAMESPACE, EXTERNAL_ID))
                .thenReturn(Optional.of(document));
        lenient().when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
    }

    private DocumentSyncRunItemRequest textRequest() {
        return new DocumentSyncRunItemRequest(
                DocumentSyncDocumentKind.TEXT,
                EXTERNAL_ID,
                REVISION,
                "Article",
                BODY,
                null,
                null,
                "connector://x",
                "text",
                Map.of(),
                EmbeddingPolicy.SKIP);
    }

    private DocumentSyncRunItemRequest jsonRequest(ObjectNode payload) {
        return new DocumentSyncRunItemRequest(
                DocumentSyncDocumentKind.JSON_RECORD,
                EXTERNAL_ID,
                REVISION,
                "Article",
                BODY,
                BODY,
                payload,
                "connector://x",
                null,
                Map.of(),
                EmbeddingPolicy.SKIP);
    }

    private DocumentRevisionConflictException syncItemConflict() {
        return assertThrows(DocumentRevisionConflictException.class,
                () -> service.upsertSyncRunItemInCurrentTransaction(
                        COLLECTION_ID, NAMESPACE, NAMESPACE,
                        textRequest(), 0L));
    }

    // ─── 守卫第 1 段：allowReconciliationRecovery 为假 ─────────────────

    @Test
    void plainExternalUpsertRefusesRecoveryBecauseRecoveryNotAllowed() {
        // 同样的墓碑、同样的 revision，受管字段也一致——但普通外部 upsert
        // 不开启对账恢复，必须报冲突。恢复能力只属于同步运行条目路径。
        RagDocument tombstone = reconciliationTombstone();
        RagCollection collection = new RagCollection();
        collection.setId(COLLECTION_ID);
        collection.setCollectionKey("kb");
        when(collectionResolver.requireActive(null, "kb"))
                .thenReturn(collection);
        stubExisting(tombstone);

        ExternalDocumentUpsertRequest request = new ExternalDocumentUpsertRequest();
        request.setCollectionKey("kb");
        request.setSourceNamespace(NAMESPACE);
        request.setExternalId(EXTERNAL_ID);
        request.setSourceRevision(REVISION);
        request.setTitle("Article");
        request.setContent(BODY);
        request.setSource("connector://x");
        request.setDocumentType("text");
        request.setEmbed(false);
        request.setEmbeddingPolicy(EmbeddingPolicy.SKIP);

        DocumentRevisionConflictException error =
                assertThrows(DocumentRevisionConflictException.class,
                        () -> service.upsertExternal(request));

        assertTrue(error.getMessage()
                .contains("same sourceRevision was used for different managed fields"),
                () -> "unexpected message: " + error.getMessage());
    }

    // ─── 守卫第 2 段：deletionOrigin 不是 RECONCILIATION ───────────────

    @Test
    void recoveryRefusedWhenDeletionOriginIsNotReconciliation() {
        RagDocument tombstone = reconciliationTombstone();
        // 外部侧主动删除的墓碑同样可以被复活吗？不可以——复活是
        // "对账以为没了、源侧其实还在" 的补偿，不是撤销用户删除。
        tombstone.setDeletionOrigin("EXTERNAL_DELETE");
        stubExisting(tombstone);

        syncItemConflict();
    }

    @Test
    void recoveryRefusedWhenDeletionOriginIsNull() {
        RagDocument tombstone = reconciliationTombstone();
        tombstone.setDeletionOrigin(null);
        stubExisting(tombstone);

        syncItemConflict();
    }

    // ─── 守卫第 3 段：sourceDeletedAt 为空 ─────────────────────────────

    @Test
    void recoveryRefusedWhenDocumentWasNotSourceDeleted() {
        // 仅禁用、未被源侧删除：这不是墓碑，没有可恢复的对象。
        RagDocument document = baseDocument();
        document.setEnabled(Boolean.FALSE);
        document.setDeletionOrigin("RECONCILIATION");
        document.setSourceDeletedAt(null);
        stubExisting(document);

        syncItemConflict();
    }

    // ─── 守卫第 4 段：受管字段逐项不等 ─────────────────────────────────
    //
    // 六项比较按 title → contentHash → source → documentType → metadata
    // → jsonbPayload 的顺序短路，所以每一项都要求它之前的项相等，
    // 否则测试会在更早的一项就返回，测不到目标分支。

    @Test
    void recoveryRefusedWhenTitleDiffers() {
        RagDocument tombstone = reconciliationTombstone();
        tombstone.setTitle("Other title");
        stubExisting(tombstone);

        syncItemConflict();
    }

    @Test
    void recoveryRefusedWhenContentDiffers() {
        RagDocument tombstone = reconciliationTombstone();
        tombstone.setContentHash(DigestUtils.sha256("Different body"));
        stubExisting(tombstone);

        syncItemConflict();
    }

    @Test
    void recoveryRefusedWhenSourceDiffers() {
        RagDocument tombstone = reconciliationTombstone();
        tombstone.setSource("connector://y");
        stubExisting(tombstone);

        syncItemConflict();
    }

    @Test
    void recoveryRefusedWhenDocumentTypeDiffers() {
        RagDocument tombstone = reconciliationTombstone();
        tombstone.setDocumentType("markdown");
        stubExisting(tombstone);

        syncItemConflict();
    }

    @Test
    void recoveryRefusedWhenMetadataDiffers() {
        RagDocument tombstone = reconciliationTombstone();
        tombstone.setMetadata(Map.of("lang", "zh"));
        stubExisting(tombstone);

        syncItemConflict();
    }

    @Test
    void recoveryRefusedWhenJsonbPayloadDiffers() {
        // 文本文档的 payload 两侧都是 null、恒相等，这一项只有结构化
        // 记录才测得到——也正是过去被漏掉的那一项。
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode stored = mapper.createObjectNode().put("k", "stored");
        ObjectNode incoming = mapper.createObjectNode().put("k", "incoming");

        RagDocument tombstone = reconciliationTombstone();
        tombstone.setDocumentType(RagDocument.JSON_RECORD);
        tombstone.setJsonbPayload(stored);
        stubExisting(tombstone);

        // 结构化记录走的是 StructuredRecordConflictException（两者都继承
        // RagException，但不是彼此的子类），所以这里断言共同基类再校验文案，
        // 免得把"抛错了某个异常"误当成"抛对了那个异常"。
        RagException error =
                assertThrows(RagException.class,
                        () -> service.upsertSyncRunItemInCurrentTransaction(
                                COLLECTION_ID, NAMESPACE, NAMESPACE,
                                jsonRequest(incoming), 0L));

        assertEquals(ErrorCode.STRUCTURED_RECORD_CONFLICT,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("different managed fields"),
                () -> "unexpected message: " + error.getMessage());
    }

    // ─── 接受侧仍然必须成立（防止上面的拒绝把恢复整体打死） ────────────

    @Test
    void reconciliationTombstoneStillRecoversWhenEveryConditionHolds() {
        stubExisting(reconciliationTombstone());

        DocumentMutationService.SyncItemMutation mutation =
                service.upsertSyncRunItemInCurrentTransaction(
                        COLLECTION_ID, NAMESPACE, NAMESPACE,
                        textRequest(), 0L);

        assertEquals(DocumentSyncItemStatus.APPLIED, mutation.status());
        assertEquals(REVISION, mutation.sourceRevision());
    }
}
