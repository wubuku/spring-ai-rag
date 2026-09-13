package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService 收尾（Batch 390）：按外部身份读取的集合解析
 * 守卫、类型强制与 detail 回读。
 */
class JsonRecordServiceIdentityTest {

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver resolver;
    private DocumentVersionService versionService;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        resolver = mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        var embeddingProfileProvider = mock(EmbeddingProfileProvider.class);
        lenient().when(embeddingProfileProvider.getActiveProfile())
                .thenReturn(new com.springairag.core.config.EmbeddingProfile(
                        9L, "profile", "test", "model", "v1",
                        1024, "COSINE", "NONE", true));
        lenient().when(resolver.beginActiveWrite(7L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(7L, 0L));
        lenient().when(resolver.resolveActiveIds(
                org.mockito.ArgumentMatchers.isNull(), eq(List.of("kb"))))
                .thenReturn(List.of(7L));
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(1);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service = new JsonRecordService(
                documentRepository,
                versionService,
                mock(DocumentEmbedService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                embeddingProfileProvider,
                resolver,
                new RagProperties(),
                new ObjectMapper(),
                mock(JdbcTemplate.class),
                null);

        // 先经 upsert 建立一条 json-record 文档。
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(7L);
        request.setExternalId("rec-1");
        request.setTitle("Record");
        request.setRetrievalText("text");
        try {
            request.setJsonbPayload(new ObjectMapper().readTree("{\"k\":1}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(documentRepository
                .findByCollectionIdAndDocumentTypeAndExternalId(
                        eq(7L), eq(RagDocument.JSON_RECORD), eq("rec-1")))
                .thenReturn(Optional.empty());
        service.upsert(request);
    }

    @Test
    void getByExternalIdentityRejectsUnknownDocument() {
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndDocumentTypeAndExternalId(
                        eq(7L), eq("default"), eq(RagDocument.JSON_RECORD),
                        eq("ghost")))
                .thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class,
                () -> service.getByExternalIdentity(
                        "kb", "default", "ghost"));
    }

    @Test
    void getByExternalIdentityReturnsDetailForStoredRecord() {
        RagDocument stored = new RagDocument();
        stored.setId(41L);
        stored.setCollectionId(7L);
        stored.setDocumentType(RagDocument.JSON_RECORD);
        stored.setExternalId("rec-1");
        stored.setTitle("Record");
        stored.setEnabled(Boolean.TRUE);
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndDocumentTypeAndExternalId(
                        eq(7L), eq("default"), eq(RagDocument.JSON_RECORD),
                        eq("rec-1")))
                .thenReturn(Optional.of(stored));
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(stored));
        when(versionService.getLatestVersion(41L))
                .thenReturn(Optional.empty());

        var detail = service.getByExternalIdentity("kb", "default", "rec-1");

        assertNotNull(detail);
        assertEquals(41L, detail.documentId());
    }
}
