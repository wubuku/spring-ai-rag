package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 集合导入入口：本地导入与 json-record 校验。 */
class DocumentMutationImportTest {

    private DocumentMutationService service;
    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private EmbeddingDispatchService dispatchService;

    @BeforeEach
    void setUp() {
        RagDocumentRepository documentRepository = mock(RagDocumentRepository.class);
        this.documentRepository = documentRepository;
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        CollectionIdentityResolver resolver = mock(CollectionIdentityResolver.class);
        DocumentVersionService versionService = mock(DocumentVersionService.class);
        this.versionService = versionService;
        EmbeddingDispatchService dispatchService = mock(EmbeddingDispatchService.class);
        this.dispatchService = dispatchService;
        when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenReturn(new com.springairag.core.entity.RagDocumentVersion());
        DocumentEmbedService documentEmbedService = mock(DocumentEmbedService.class);
        DocumentLifecycleService lifecycleService = mock(DocumentLifecycleService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));

        service = new DocumentMutationService(
                documentRepository,
                embeddingRepository,
                resolver,
                versionService,
                dispatchService,
                documentEmbedService,
                lifecycleService,
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);
    }

    private CollectionImportRequest.ImportedDocument localImport() {
        CollectionImportRequest.ImportedDocument imported =
                new CollectionImportRequest.ImportedDocument();
        imported.setTitle("Imported Doc");
        imported.setContent("Imported content");
        imported.setSource("import.txt");
        imported.setDocumentType("text");
        imported.setMetadata(Map.of("locale", "zh-CN"));
        imported.setEnabled(false);
        return imported;
    }

    @Test
    void rejectsJsonRecordImportWithoutExternalIdPayload() {
        CollectionImportRequest.ImportedDocument imported =
                new CollectionImportRequest.ImportedDocument();
        imported.setTitle("json-doc");
        imported.setContent("body");
        imported.setExternalId("cms:1");
        imported.setDocumentType(RagDocument.JSON_RECORD);
        imported.setJsonbPayload(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.importDocument(5L, "kb", imported));
    }

    @Test
    void rejectsJsonRecordImportWithNullPayload() {
        CollectionImportRequest.ImportedDocument imported =
                new CollectionImportRequest.ImportedDocument();
        imported.setTitle("json-doc");
        imported.setContent("body");
        imported.setExternalId("cms:1");
        imported.setDocumentType(RagDocument.JSON_RECORD);
        imported.setJsonbPayload(new ObjectMapper().createObjectNode().nullNode());

        assertThrows(IllegalArgumentException.class,
                () -> service.importDocument(5L, "kb", imported));
    }

    @Test
    void localImportPersistsWithNoneDedupAndSkipPolicy() {
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    doc.setId(99L);
                    return doc;
                });
        when(documentRepository.findById(99L))
                .thenReturn(Optional.of(new RagDocument()));

        CollectionImportRequest.ImportedDocument imported = localImport();
        service.importDocument(5L, "kb", imported);

        ArgumentCaptor<RagDocument> saved =
                ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).saveAndFlush(saved.capture());
        assertEquals("Imported Doc", saved.getValue().getTitle());
        assertEquals("import.txt", saved.getValue().getSource());
        assertEquals(Boolean.FALSE, saved.getValue().getEnabled());
        // SKIP 策略不排队向量嵌入。
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void rejectsJsonRecordImportWithExternalIdAndNullPayload() {
        CollectionImportRequest.ImportedDocument imported =
                new CollectionImportRequest.ImportedDocument();
        imported.setTitle("json-doc");
        imported.setContent("body");
        imported.setExternalId("cms:1");
        imported.setDocumentType(RagDocument.JSON_RECORD);
        imported.setJsonbPayload(new ObjectMapper().createObjectNode().nullNode());

        assertThrows(IllegalArgumentException.class,
                () -> service.importDocument(5L, "kb", imported));
    }
}
