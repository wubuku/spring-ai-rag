package com.springairag.core.service;

import com.springairag.api.dto.ExternalDocumentDeleteResponse;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.dto.ExternalDocumentUpsertResponse;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalDocumentServiceTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            7L, "test-profile", "test", "test-model", "v1",
            1024, "COSINE", "PROVIDER_DEFAULT", true);

    @Mock
    private RagDocumentRepository documentRepository;
    @Mock
    private RagCollectionRepository collectionRepository;
    @Mock
    private RagEmbeddingRepository embeddingRepository;
    @Mock
    private DocumentVersionService documentVersionService;
    @Mock
    private DocumentEmbedService documentEmbedService;
    @Mock
    private EmbeddingProfileProvider embeddingProfileProvider;
    @Mock
    private CollectionIdentityResolver collectionIdentityResolver;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private RagCollection collection;
    private ExternalDocumentService service;

    @BeforeEach
    void setUp() {
        collection = new RagCollection();
        collection.setId(10L);
        collection.setCollectionKey("customer-42:manual:v1");
        collection.setName("Manual");
        collection.setEnabled(true);

        lenient().when(collectionIdentityResolver.requireActive(null, collection.getCollectionKey()))
                .thenReturn(collection);
        lenient().when(embeddingProfileProvider.getActiveProfile()).thenReturn(PROFILE);
        lenient().when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(null);
        lenient().when(collectionRepository.findById(10L)).thenReturn(Optional.of(collection));
        lenient().when(embeddingRepository.countFreshChunksByDocumentIdAndProfileId(
                anyLong(), eq(PROFILE.id()))).thenReturn(0L);
        lenient().when(documentVersionService.forceRecordVersion(
                any(RagDocument.class), any(String.class), any(String.class)))
                .thenAnswer(invocation -> version(1));
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument document = invocation.getArgument(0);
                    if (document.getId() == null) {
                        document.setId(41L);
                    }
                    return document;
                });

        service = new ExternalDocumentService(
                documentRepository,
                collectionRepository,
                embeddingRepository,
                documentVersionService,
                documentEmbedService,
                embeddingProfileProvider,
                collectionIdentityResolver,
                jdbcTemplate,
                null);
    }

    @Test
    void createPersistsStableIdentityAndEmbeds() {
        ExternalDocumentUpsertRequest request = request(
                "doc-1", "rev-1", "First title", "First content");
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.empty());
        when(documentEmbedService.embedDocument(41L, false))
                .thenReturn(Map.of(
                        "status", "COMPLETED",
                        "embeddingProfileKey", PROFILE.profileKey()));
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(false, true);

        ExternalDocumentUpsertResponse response = service.upsert(request);

        assertEquals(41L, response.documentId());
        assertEquals("CREATED", response.action());
        assertTrue(response.contentChanged());
        assertEquals("COMPLETED", response.embeddingStatus());
        assertTrue(response.embeddingFresh());
        verify(documentEmbedService).embedDocument(41L, false);
        verify(documentVersionService).forceRecordVersion(
                any(RagDocument.class), eq("CREATE"), any(String.class));
    }

    @Test
    void exactReplayIsUnchangedAndSkipsPersistenceAndEmbedding() {
        RagDocument document = document(41L, "doc-1", "rev-1", "First content");
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(document));
        when(documentVersionService.getLatestVersion(41L))
                .thenReturn(Optional.of(version(3)));
        when(documentEmbedService.hasFreshEmbedding(document)).thenReturn(true);

        ExternalDocumentUpsertResponse response = service.upsert(
                request("doc-1", "rev-1", "First title", "First content"));

        assertEquals("UNCHANGED", response.action());
        assertEquals(3, response.versionNumber());
        assertEquals("CACHED", response.embeddingStatus());
        verify(documentRepository, never()).saveAndFlush(any(RagDocument.class));
        verify(documentVersionService, never()).forceRecordVersion(
                any(RagDocument.class), any(String.class), any(String.class));
        verify(documentEmbedService, never()).embedDocument(anyLong(), eq(false));
    }

    @Test
    void sameRevisionWithDifferentContentIsRejected() {
        RagDocument document = document(41L, "doc-1", "rev-1", "Old content");
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(document));

        assertThrows(DocumentRevisionConflictException.class,
                () -> service.upsert(request(
                        "doc-1", "rev-1", "First title", "New content")));

        verify(documentRepository, never()).saveAndFlush(any(RagDocument.class));
        verify(documentEmbedService, never()).embedDocument(anyLong(), eq(false));
    }

    @Test
    void compareAndSetRejectsStaleExpectedRevision() {
        RagDocument document = document(41L, "doc-1", "rev-2", "Current content");
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(document));
        ExternalDocumentUpsertRequest request =
                request("doc-1", "rev-3", "Next", "Next content");
        request.setExpectedSourceRevision("rev-1");

        assertThrows(DocumentRevisionConflictException.class,
                () -> service.upsert(request));

        assertEquals("rev-2", document.getSourceRevision());
        assertEquals("Current content", document.getContent());
        verify(documentRepository, never()).saveAndFlush(any(RagDocument.class));
    }

    @Test
    void contentUpdateKeepsDocumentIdAndReportsEmbeddingFailure() {
        RagDocument document = document(41L, "doc-1", "rev-1", "Old content");
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(document));
        when(documentRepository.findById(41L)).thenReturn(Optional.of(document));
        when(documentVersionService.forceRecordVersion(
                any(RagDocument.class), eq("UPDATE"), any(String.class)))
                .thenReturn(version(2));
        when(documentEmbedService.hasFreshEmbedding(document)).thenReturn(false);
        when(documentEmbedService.embedDocument(41L, false))
                .thenReturn(Map.of("status", "FAILED", "error", "provider unavailable"));

        ExternalDocumentUpsertRequest request =
                request("doc-1", "rev-2", "Updated", "New content");
        request.setExpectedSourceRevision("rev-1");

        ExternalDocumentUpsertResponse response = service.upsert(request);

        assertEquals(41L, response.documentId());
        assertEquals("UPDATED", response.action());
        assertTrue(response.contentChanged());
        assertEquals("FAILED", response.embeddingStatus());
        assertFalse(response.embeddingFresh());
        assertEquals("New content", document.getContent());
        assertEquals("rev-2", document.getSourceRevision());
        assertSame(document, documentRepository.findById(41L).orElseThrow());
    }

    @Test
    void sourceDeleteCreatesTombstoneAndReplayIsUnchanged() {
        RagDocument document = document(41L, "doc-1", "rev-1", "Content");
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(document));
        when(documentVersionService.forceRecordVersion(
                any(RagDocument.class), eq("DELETE"), any(String.class)))
                .thenReturn(version(2));

        ExternalDocumentDeleteResponse deleted = service.sourceDelete(
                collection.getCollectionKey(), "doc-1", "rev-2", "rev-1");

        assertEquals("DELETED", deleted.action());
        assertFalse(deleted.enabled());
        assertEquals("rev-2", document.getSourceRevision());
        assertFalse(document.getEnabled());
        assertTrue(document.getSourceDeletedAt() != null);

        ExternalDocumentDeleteResponse replay = service.sourceDelete(
                collection.getCollectionKey(), "doc-1", "rev-2", null);
        assertEquals("UNCHANGED", replay.action());
        verify(documentRepository).saveAndFlush(document);
    }

    private ExternalDocumentUpsertRequest request(
            String externalId, String revision, String title, String content) {
        ExternalDocumentUpsertRequest request = new ExternalDocumentUpsertRequest();
        request.setCollectionKey(collection.getCollectionKey());
        request.setExternalId(externalId);
        request.setSourceRevision(revision);
        request.setTitle(title);
        request.setContent(content);
        request.setSource("connector://manual");
        request.setDocumentType("text");
        request.setEmbed(true);
        return request;
    }

    private RagDocument document(
            Long id, String externalId, String revision, String content) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setCollectionId(collection.getId());
        document.setExternalId(externalId);
        document.setSourceRevision(revision);
        document.setTitle("First title");
        document.setContent(content);
        document.setSource("connector://manual");
        document.setDocumentType("text");
        document.setContentHash(com.springairag.core.util.DigestUtils.sha256(content));
        document.setEnabled(true);
        document.setProcessingStatus("COMPLETED");
        return document;
    }

    private RagDocumentVersion version(int number) {
        RagDocumentVersion version = new RagDocumentVersion();
        version.setVersionNumber(number);
        return version;
    }
}
