package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService 第二扫（Batch 377）：详细检索的 rerank 降级
 * 与查询守卫、getDetail 的缺失/类型拒绝、批量 upsert 汇总计数。
 */
class JsonRecordServiceMidTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagDocumentRepository documentRepository;
    private HybridRetrieverService hybridRetrieverService;
    private ReRankingService reRankingService;
    private DocumentMutationService mutationService;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        hybridRetrieverService = mock(HybridRetrieverService.class);
        reRankingService = mock(ReRankingService.class);
        mutationService = mock(DocumentMutationService.class);
        service = new JsonRecordService(
                documentRepository,
                mock(DocumentVersionService.class),
                hybridRetrieverService,
                reRankingService,
                mock(CollectionIdentityResolver.class),
                new RagProperties(),
                MAPPER,
                mock(JdbcTemplate.class),
                null,
                mutationService);
    }

    private JsonRecordUpsertRequest validRequest(String externalId) {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(7L);
        request.setExternalId(externalId);
        request.setTitle("Title " + externalId);
        request.setRetrievalText("text");
        try {
            request.setJsonbPayload(MAPPER.readTree("{\"k\":1}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return request;
    }

    private JsonRecordUpsertResponse response(String action,
                                              String embeddingStatus) {
        return new JsonRecordUpsertResponse(
                41L, 7L, "kb", "doc-1", action, true, true, 1,
                embeddingStatus, "profile", null, null, null, null);
    }

    @Test
    void searchDetailedRejectsBlankQueryAndInvalidMaxResults() {
        RetrievalScope scope = RetrievalScope.anyAssigned(null, null);
        RetrievalConfig config = RetrievalConfig.builder().maxResults(5).build();

        assertThrows(IllegalArgumentException.class, () ->
                service.searchAuthorizedDetailed(
                        "  ", RetrievalFilters.none(), null, scope, config));
        assertThrows(IllegalArgumentException.class, () ->
                service.searchAuthorizedDetailed(
                        "query", RetrievalFilters.none(), null, scope,
                        RetrievalConfig.builder().maxResults(0).build()));
    }

    @Test
    void searchDetailedDegradesGracefullyWhenRerankFails() {
        // 夹具原先给的是两个 new RetrievalResult()——documentId 为 null。
        // 而 searchAuthorizedDetailed 在 rerank 之后要按 documentId 去重，
        // parseDocumentId(null) 返回 null，两条都被丢进"无唯一结果"分支，
        // 于是结果恒为空。降级逻辑明明跑了（catch 分支把 beforeRerank 原样
        // 留了下来），这条用例却什么都看不见。
        // 补上 documentId，降级保留才真的可观测。
        RetrievalResult first = new RetrievalResult();
        first.setDocumentId("41");
        RetrievalResult second = new RetrievalResult();
        second.setDocumentId("42");
        List<RetrievalResult> results = List.of(first, second);
        RetrievalOutcome outcome = RetrievalOutcome.ofResults(results);
        when(hybridRetrieverService.searchInScopeDetailed(
                anyString(), any(), any(), anyInt(),
                any(RetrievalConfig.class), any(RetrievalFilters.class)))
                .thenReturn(outcome);
        when(reRankingService.rerank(anyString(), any(List.class), anyInt()))
                .thenThrow(new IllegalStateException("rerank down"));
        // 夹具还缺最后一环：结果要能落到"JSON 记录文档"上才会出现在响应里。
        // searchAuthorizedDetailed 先按 documentId 去重，再拿这些 id 去
        // findByIdInAndDocumentTypeAndEnabledTrue 捞文档；这步没打桩时
        // Mockito 默认返回空表，于是每一条都被丢掉——降级保没保住，
        // 从外面看都是一样的空结果。
        when(documentRepository.findByIdInAndDocumentTypeAndEnabledTrue(
                anyList(), eq(com.springairag.core.entity.RagDocument.JSON_RECORD)))
                .thenReturn(List.of(jsonRecordDocument(41L, 7L),
                        jsonRecordDocument(42L, 7L)));

        var detail = service.searchAuthorizedDetailed(
                "query", RetrievalFilters.none(), null,
                RetrievalScope.anyAssigned(null, null),
                RetrievalConfig.builder().maxResults(5)
                        .useRerank(true).build());

        // rerank 降级后走 limitResults 保留原有结果集。
        assertNotNull(detail);
        assertNotNull(detail.response());
        // Batch 958：原来只断到 response 非空。"DegradesGracefully" 的全部
        // 内容是"rerank 炸了但结果集还在"——那就数一遍结果还在不在。
        // rerank 抛异常时若被吞成空结果集，这两条 assertNotNull 照样绿。
        assertEquals(2, detail.traceResults().size(),
                "rerank 失败后原始结果集必须原样保留：" + detail.traceResults());
        // 响应里映出来的那份也得是同一批
        assertEquals(detail.traceResults().size(),
                detail.response().results().size());
    }

    @Test
    void getDetailRejectsMissingAndNonJsonRecordDocuments() {
        when(documentRepository.findById(41L))
                .thenReturn(java.util.Optional.empty());
        assertThrows(DocumentNotFoundException.class,
                () -> service.getDetail(41L));

        com.springairag.core.entity.RagDocument textDocument =
                new com.springairag.core.entity.RagDocument();
        textDocument.setDocumentType("text");
        when(documentRepository.findById(41L))
                .thenReturn(java.util.Optional.of(textDocument));
        assertThrows(DocumentNotFoundException.class,
                () -> service.getDetail(41L));
    }

    private DocumentMutationService.JsonMutationResult result(
            String action, boolean contentChanged, boolean payloadChanged,
            int version) {
        com.springairag.core.entity.RagDocument document =
                new com.springairag.core.entity.RagDocument();
        document.setId(41L);
        document.setCollectionId(7L);
        document.setExternalId("doc-1");
        var lifecycle = new com.springairag.api.dto.DocumentLifecycleResponse(
                "ACTIVE", "SEARCHABLE", "CURRENT", "COMPLETED",
                "profile", null, null, null, false);
        return new DocumentMutationService.JsonMutationResult(
                document, action, contentChanged, payloadChanged,
                version, null, lifecycle);
    }

    @Test
    void batchUpsertAggregatesSummaryCounts() {
        JsonRecordUpsertRequest created = validRequest("doc-1");
        JsonRecordUpsertRequest updated = validRequest("doc-2");
        JsonRecordUpsertRequest unchanged = validRequest("doc-3");
        JsonRecordUpsertRequest broken = validRequest("doc-4");
        when(mutationService.upsertJsonRecord(
                eq(created), anyLong(), any(), any(), any()))
                .thenReturn(result("CREATED", true, true, 1));
        when(mutationService.upsertJsonRecord(
                eq(updated), anyLong(), any(), any(), any()))
                .thenReturn(result("UPDATED", true, true, 2));
        when(mutationService.upsertJsonRecord(
                eq(unchanged), anyLong(), any(), any(), any()))
                .thenReturn(result("UNCHANGED", false, false, 2));
        when(mutationService.upsertJsonRecord(
                eq(broken), anyLong(), any(), any(), any()))
                .thenThrow(new IllegalStateException("persist broken"));

        var batch = service.batchUpsert(List.of(
                created, updated, unchanged, broken));

        assertEquals(4, batch.summary().total());
        assertEquals(1, batch.summary().created());
        assertEquals(1, batch.summary().updated());
        assertEquals(1, batch.summary().unchanged());
        assertEquals(1, batch.summary().persistenceFailed());
        assertEquals(0, batch.summary().embeddingFailed());
    }

    /** 一条可用的 JSON 记录文档：id / collectionId / 类型 / 启用都要齐。 */
    private com.springairag.core.entity.RagDocument jsonRecordDocument(
            Long id, Long collectionId) {
        com.springairag.core.entity.RagDocument doc =
                new com.springairag.core.entity.RagDocument();
        doc.setId(id);
        doc.setCollectionId(collectionId);
        doc.setDocumentType(com.springairag.core.entity.RagDocument.JSON_RECORD);
        doc.setEnabled(Boolean.TRUE);
        doc.setTitle("record-" + id);
        doc.setContent("{}");
        doc.setSource("upload");
        return doc;
    }
}
