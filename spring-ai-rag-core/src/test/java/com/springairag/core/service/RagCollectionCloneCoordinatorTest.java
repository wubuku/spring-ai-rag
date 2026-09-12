package com.springairag.core.service;

import com.springairag.api.dto.CollectionCloneResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * cloneCollection 协调器分支与约束映射（Batch 328）：突变成员服
 * 务在位时逐文档经 createLocal 克隆（ASYNCC/去重 NONE/来源继承）、
 * legacy 路径带版本记录、键唯一约束冲突映射为 DUPLICATE_RESOURCE、
 * 其他完整性冲突原样上抛。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagCollectionCloneCoordinatorTest {

    @Mock
    private RagCollectionRepository collectionRepository;

    @Mock
    private RagDocumentRepository documentRepository;

    private DocumentMutationService documentMutationService;
    private DocumentVersionService documentVersionService;

    @BeforeEach
    void setUp() {
        documentMutationService = mock(DocumentMutationService.class);
        documentVersionService = mock(DocumentVersionService.class);
        when(collectionRepository.saveAndFlush(any(RagCollection.class)))
                .thenAnswer(invocation -> {
                    RagCollection collection = invocation.getArgument(0);
                    collection.setId(5L);
                    return collection;
                });
        when(collectionRepository.existsByCollectionKey("clone-key"))
                .thenReturn(false);
        // 真实 CollectionIdentityResolver 的活动写 CAS 需命中。
        when(collectionRepository.advanceActiveVersion(
                any(Long.class), any(Long.class))).thenReturn(1);
    }

    private RagDocument sourceDocument(long id, String title) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setTitle(title);
        document.setContent("content-" + id);
        document.setSource("unit-test");
        document.setDocumentType("PDF");
        document.setCollectionId(1L);
        document.setEnabled(true);
        document.setOriginalFilename("orig-" + id + ".pdf");
        document.setProcessingStatus("COMPLETED");
        return document;
    }

    private void stubSourceCollection() {
        when(collectionRepository.findByIdAndDeletedFalse(1L))
                .thenReturn(Optional.of(sourceCollection()));
    }

    private RagCollection sourceCollection() {
        RagCollection collection = new RagCollection();
        collection.setId(1L);
        collection.setCollectionKey("source");
        collection.setName("Source");
        collection.setDescription("Source description");
        collection.setEmbeddingModel("bge-m3");
        collection.setDimensions(1024);
        collection.setEnabled(true);
        return collection;
    }

    @Test
    void coordinatorPathDelegatesPerDocumentWithoutDirectSave() {
        stubSourceCollection();
        when(documentRepository.findAllByCollectionId(1L))
                .thenReturn(List.of(
                        sourceDocument(10L, "Doc 1"),
                        sourceDocument(11L, "Doc 2")));

        RagCollectionService service =
                new RagCollectionService(collectionRepository, documentRepository, null);
        service.setDocumentMutationService(documentMutationService);

        Optional<CollectionCloneResponse> result =
                service.cloneCollection(1L, "clone-key");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().documentsCloned());
        // 每个来源文档经突变服务克隆，继承来源与禁用去重。
        ArgumentCaptor<DocumentRequest> requests =
                ArgumentCaptor.forClass(DocumentRequest.class);
        verify(documentMutationService, times(2)).createLocal(
                requests.capture(), eq(5L), eq(EmbeddingPolicy.ASYNC),
                eq(false), eq("COLLECTION_CLONE"), eq(null),
                any(), eq(null), eq(true));
        assertEquals("Doc 1", requests.getAllValues().get(0).getTitle());
        assertEquals("content-11",
                requests.getAllValues().get(1).getContent());
        // 协调器路径不直接批量保存。
        verify(documentRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void legacyPathRecordsVersionsPerClonedDocument() {
        stubSourceCollection();
        RagDocument doc1 = sourceDocument(10L, "Doc 1");
        when(documentRepository.findAllByCollectionId(1L))
                .thenReturn(List.of(doc1));
        when(documentRepository.saveAllAndFlush(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RagCollectionService service =
                new RagCollectionService(collectionRepository, documentRepository, null);
        service.setDocumentVersionService(documentVersionService);

        Optional<CollectionCloneResponse> result =
                service.cloneCollection(1L, "clone-key");

        assertEquals(1, result.get().documentsCloned());
        verify(documentVersionService).forceRecordVersion(
                any(RagDocument.class), eq("CREATE"),
                eq("Cloned from document 10 in collection 1"));
        verify(documentMutationService, never()).createLocal(
                any(), any(), any(), any(Boolean.class), anyString(),
                any(), any(), any(), any());
    }

    @Test
    void keyConstraintViolationMapsToDuplicateResource() {
        stubSourceCollection();
        when(documentRepository.findAllByCollectionId(1L))
                .thenReturn(List.of());
        when(collectionRepository.saveAndFlush(any(RagCollection.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "constraint violation",
                        new org.hibernate.exception.ConstraintViolationException(
                                "dup", new java.sql.SQLException("dup"),
                                "uk_rag_collection_collection_key")));

        RagCollectionService service =
                new RagCollectionService(collectionRepository, documentRepository, null);
        service.setDocumentMutationService(documentMutationService);

        RagException error = assertThrows(RagException.class,
                () -> service.cloneCollection(1L, "clone-key"));

        assertEquals(RagException.class, error.getClass());
        assertTrue(error.getMessage().contains("already exists"));
    }

    @Test
    void otherIntegrityViolationsAreRethrownAsIs() {
        stubSourceCollection();
        when(documentRepository.findAllByCollectionId(1L))
                .thenReturn(List.of());
        DataIntegrityViolationException unrelated =
                new DataIntegrityViolationException("other constraint");
        when(collectionRepository.saveAndFlush(any(RagCollection.class)))
                .thenThrow(unrelated);

        RagCollectionService service =
                new RagCollectionService(collectionRepository, documentRepository, null);
        service.setDocumentMutationService(documentMutationService);

        DataIntegrityViolationException thrown =
                assertThrows(DataIntegrityViolationException.class,
                        () -> service.cloneCollection(1L, "clone-key"));

        assertSame(unrelated, thrown);
    }
}
