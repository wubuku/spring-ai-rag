package com.springairag.core.service;

import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Batch 834 夹具：{@code upsert} 委托 {@link DocumentMutationService} 之后，
 * JsonRecordService 这层真正要钉的是「把什么交出去、把什么映射回来」，
 * 而不是已经搬走的内联落库 / 同步嵌入实现。
 *
 * <p><b>集合身份必须从调用实参取</b>：{@code upsert} 传给
 * {@code upsertJsonRecord} 的是 {@code request.getCollectionId()} 与
 * {@code requestCollectionKey(request)} 的解析结果，两者都不在
 * {@link JsonRecordUpsertRequest} 对象上（键在 {@code collectionKey} 字段，
 * 但解析走的是 identityResolver）。从 request 上取会恒为 null，
 * 夹具静默失配、测试照样绿——Batch 830/832 连续两次栽过这一处。
 */
final class JsonRecordMutationFixture {

    static final long COLLECTION_ID = 10L;
    static final String COLLECTION_KEY = "kb-10";

    private JsonRecordMutationFixture() {
    }

    static RagDocument document(long id, String externalId) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setCollectionId(COLLECTION_ID);
        document.setExternalId(externalId);
        document.setSourceNamespace("default");
        document.setSourceRevision("rev-9");
        document.setDocumentRevision(3L);
        return document;
    }

    static DocumentLifecycleResponse lifecycle(String embeddingStatus) {
        return new DocumentLifecycleResponse(
                "LIVE", "SEARCHABLE", "COMPLETED", embeddingStatus, "bge-m3",
                null, null, null, false);
    }

    /**
     * 让 mutationService 按<b>调用实参</b>的集合身份造结果，同时把该 id
     * 映回 {@link #COLLECTION_KEY}（响应里的 collectionKey 走的是
     * {@code identityResolver.mapKeys}，不是入参）。
     */
    static void upserts(DocumentMutationService mutationService,
                        CollectionIdentityResolver identityResolver,
                        String action,
                        boolean contentChanged,
                        boolean payloadChanged,
                        int versionNumber) {
        lenient().when(identityResolver.mapKeys(any()))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(0);
                    return Map.of(ids.get(0), COLLECTION_KEY);
                });
        lenient().when(mutationService.upsertJsonRecord(
                any(JsonRecordUpsertRequest.class),
                any(Long.class),
                anyString(),
                any(),
                any())).thenAnswer(invocation -> {
                    Long collectionId = invocation.getArgument(1);
                    String externalId = ((JsonRecordUpsertRequest) invocation
                            .getArgument(0)).getExternalId();
                    RagDocument document = document(
                            collectionId * 100 + 1, externalId);
                    document.setCollectionId(collectionId);
                    return new DocumentMutationService.JsonMutationResult(
                            document, action, contentChanged, payloadChanged,
                            versionNumber, null, lifecycle("NOT_REQUESTED"));
                });
    }

    /** 同上，但带一次 ASYNC 派发结果。 */
    static void upsertsWithAsyncDispatch(
            DocumentMutationService mutationService,
            CollectionIdentityResolver identityResolver,
            String action,
            UUID jobId,
            UUID batchId) {
        lenient().when(identityResolver.mapKeys(any()))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(0);
                    return Map.of(ids.get(0), COLLECTION_KEY);
                });
        EmbeddingDispatchService.Result dispatch =
                new EmbeddingDispatchService.Result(
                        EmbeddingAction.ASYNC_QUEUED, "QUEUED", "bge-m3",
                        jobId, batchId, null);
        lenient().when(mutationService.upsertJsonRecord(
                any(JsonRecordUpsertRequest.class),
                any(Long.class),
                anyString(),
                any(),
                any())).thenAnswer(invocation -> {
                    Long collectionId = invocation.getArgument(1);
                    String externalId = ((JsonRecordUpsertRequest) invocation
                            .getArgument(0)).getExternalId();
                    RagDocument document = document(
                            collectionId * 100 + 1, externalId);
                    document.setCollectionId(collectionId);
                    return new DocumentMutationService.JsonMutationResult(
                            document, action, true, false, 1,
                            dispatch, lifecycle("QUEUED"));
                });
    }

    /** 让 mutationService 抛出指定失败（用于「异常透出」类断言）。 */
    static void fails(DocumentMutationService mutationService,
                      RuntimeException failure) {
        lenient().when(mutationService.upsertJsonRecord(
                any(JsonRecordUpsertRequest.class),
                any(Long.class),
                any(),
                any(),
                any())).thenThrow(failure);
    }

    /**
     * 把一条 upsert 请求翻成导入条目。legacy {@code persist} 链在
     * Batch 834 之后只剩 {@code importRecord} 一个活入口，需要它的
     * 用例从这里取形状，而不是各写各的。
     */
    static CollectionImportRequest.ImportedDocument imported(
            JsonRecordUpsertRequest request) {
        CollectionImportRequest.ImportedDocument imported =
                new CollectionImportRequest.ImportedDocument();
        imported.setExternalId(request.getExternalId());
        imported.setTitle(request.getTitle());
        imported.setContent(request.getRetrievalText());
        imported.setJsonbPayload(request.getJsonbPayload());
        imported.setSource(request.getSource());
        imported.setMetadata(request.getMetadata());
        return imported;
    }
}
