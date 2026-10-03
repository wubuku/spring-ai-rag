package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService 解析与回读长尾（Batch 709 建立，Batch 834 改名）：
 * 请求集合解析对非唯一结果的拒绝、getDetail 在生命周期服务缺省时
 * 响应携带 null 生命周期。
 *
 * <p>Batch 834 删掉 legacy 内联落库路径后，嵌入结果三臂（已死的
 * {@code embedIfRequested}）覆盖随之移除；本类只保留仍然存活的
 * 解析守卫与回读契约。
 */
class JsonRecordServiceResolutionDetailTailTest {

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver collectionIdentityResolver;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        service = new JsonRecordService(
                documentRepository,
                mock(DocumentVersionService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                collectionIdentityResolver,
                new RagProperties(),
                new ObjectMapper(),
                mock(JdbcTemplate.class),
                null);
    }

    private JsonRecordUpsertRequest request(Long collectionId,
                                            String collectionKey) {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(collectionId);
        request.setCollectionKey(collectionKey);
        request.setExternalId("cms:article:1");
        request.setTitle("Record");
        return request;
    }

    private void invokeResolver(JsonRecordUpsertRequest request)
            throws Throwable {
        Method method = JsonRecordService.class.getDeclaredMethod(
                "resolveRequestCollection", JsonRecordUpsertRequest.class);
        method.setAccessible(true);
        try {
            method.invoke(service, request);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void resolverRejectsAmbiguousCollectionResolution() {
        when(collectionIdentityResolver.resolveActiveIds(any(), any()))
                .thenReturn(List.of(1L, 2L));

        var error = assertThrows(IllegalArgumentException.class,
                () -> invokeResolver(request(null, "kb-1")));
        assertEquals("Exactly one Collection must be provided",
                error.getMessage());
    }

    @Test
    void getDetailToleratesMissingLifecycleService() {
        RagDocument doc = new RagDocument();
        doc.setId(31L);
        doc.setDocumentType(RagDocument.JSON_RECORD);
        doc.setCollectionId(7L);
        doc.setExternalId("cms:article:1");
        when(documentRepository.findById(31L)).thenReturn(Optional.of(doc));
        when(collectionIdentityResolver.mapKeys(anyList()))
                .thenReturn(Map.of(7L, "kb-7"));

        var detail = service.getDetail(31L);

        assertEquals("cms:article:1", detail.externalId());
        assertNull(detail.lifecycle());
    }
}
