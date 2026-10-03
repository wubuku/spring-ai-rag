package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        lenient().when(resolver.beginActiveWrite(7L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(7L, 0L));
        lenient().when(resolver.resolveActiveIds(
                org.mockito.ArgumentMatchers.isNull(), eq(List.of("kb"))))
                .thenReturn(List.of(7L));

        service = new JsonRecordService(
                documentRepository,
                versionService,
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                resolver,
                new RagProperties(),
                new ObjectMapper(),
                mock(JdbcTemplate.class));
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
