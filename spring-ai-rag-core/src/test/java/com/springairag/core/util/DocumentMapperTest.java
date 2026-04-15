package com.springairag.core.util;

import com.springairag.api.dto.DocumentVersionResponse;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentMapper Tests")
class DocumentMapperTest {

    @Mock
    private RagCollectionRepository collectionRepository;

    @Mock
    private RagEmbeddingRepository embeddingRepository;

    private RagDocument sampleDocument;

    @BeforeEach
    void setUp() {
        sampleDocument = new RagDocument();
        sampleDocument.setId(1L);
        sampleDocument.setTitle("Test Document");
        sampleDocument.setSource("test-source");
        sampleDocument.setDocumentType("TEXT");
        sampleDocument.setEnabled(true);
        sampleDocument.setCreatedAt(LocalDateTime.now());
        sampleDocument.setUpdatedAt(LocalDateTime.now());
        sampleDocument.setProcessingStatus("COMPLETED");
        sampleDocument.setSize(1024L);
        sampleDocument.setContentHash("abc123");
    }

    @Nested
    @DisplayName("toMap (batch variant)")
    class BatchVariantTests {

        @Test
        @DisplayName("maps core fields correctly")
        void mapsCoreFieldsCorrectly() {
            Map<Long, String> emptyCollectionMap = new HashMap<>();
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(5L);

            Map<String, Object> result = DocumentMapper.toMap(sampleDocument, emptyCollectionMap, embeddingRepository);

            assertEquals(1L, result.get("id"));
            assertEquals("Test Document", result.get("title"));
            assertEquals("test-source", result.get("source"));
            assertEquals("TEXT", result.get("documentType"));
            assertEquals(true, result.get("enabled"));
            assertEquals("COMPLETED", result.get("processingStatus"));
            assertEquals(1024L, result.get("size"));
            assertEquals("abc123", result.get("contentHash"));
            assertEquals(5L, result.get("chunkCount"));
        }

        @Test
        @DisplayName("uses pre-built collection name map")
        void usesPrebuiltCollectionNameMap() {
            Map<Long, String> collectionMap = Map.of(10L, "Knowledge Base A");
            sampleDocument.setCollectionId(10L);
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(0L);

            Map<String, Object> result = DocumentMapper.toMap(sampleDocument, collectionMap, embeddingRepository);

            assertEquals(10L, result.get("collectionId"));
            assertEquals("Knowledge Base A", result.get("collectionName"));
            verify(collectionRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("skips collection name when not in map")
        void skipsCollectionNameWhenNotInMap() {
            Map<Long, String> emptyCollectionMap = new HashMap<>();
            sampleDocument.setCollectionId(99L);
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(0L);

            Map<String, Object> result = DocumentMapper.toMap(sampleDocument, emptyCollectionMap, embeddingRepository);

            assertEquals(99L, result.get("collectionId"));
            assertNull(result.get("collectionName"));
        }

        @Test
        @DisplayName("includes optional content and metadata")
        void includesOptionalFields() {
            sampleDocument.setContent("Document body text");
            Map<String, Object> metadata = Map.of("author", "tester");
            sampleDocument.setMetadata(metadata);
            Map<Long, String> emptyCollectionMap = new HashMap<>();
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(0L);

            Map<String, Object> result = DocumentMapper.toMap(sampleDocument, emptyCollectionMap, embeddingRepository);

            assertEquals("Document body text", result.get("content"));
            assertEquals(metadata, result.get("metadata"));
        }

        @Test
        @DisplayName("handles null content and metadata gracefully")
        void handlesNullOptionalFields() {
            sampleDocument.setContent(null);
            sampleDocument.setMetadata(null);
            Map<Long, String> emptyCollectionMap = new HashMap<>();
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(0L);

            Map<String, Object> result = DocumentMapper.toMap(sampleDocument, emptyCollectionMap, embeddingRepository);

            assertNull(result.get("content"));
            assertNull(result.get("metadata"));
        }
    }

    @Nested
    @DisplayName("toMap (single-document variant)")
    class SingleVariantTests {

        @Test
        @DisplayName("fetches collection name on demand")
        void fetchesCollectionNameOnDemand() {
            sampleDocument.setCollectionId(20L);
            RagCollection collection = new RagCollection();
            collection.setName("On-Demand Collection");
            when(collectionRepository.findById(20L)).thenReturn(Optional.of(collection));
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(0L);

            Map<String, Object> result = DocumentMapper.toMap(sampleDocument, collectionRepository, embeddingRepository);

            assertEquals(20L, result.get("collectionId"));
            assertEquals("On-Demand Collection", result.get("collectionName"));
            verify(collectionRepository).findById(20L);
        }

        @Test
        @DisplayName("handles missing collection gracefully")
        void handlesMissingCollection() {
            sampleDocument.setCollectionId(999L);
            when(collectionRepository.findById(999L)).thenReturn(Optional.empty());
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(0L);

            Map<String, Object> result = DocumentMapper.toMap(sampleDocument, collectionRepository, embeddingRepository);

            assertEquals(999L, result.get("collectionId"));
            assertNull(result.get("collectionName"));
        }

        @Test
        @DisplayName("maps same core fields as batch variant")
        void mapsSameCoreFieldsAsBatchVariant() {
            Map<Long, String> emptyCollectionMap = new HashMap<>();
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(3L);

            Map<String, Object> result = DocumentMapper.toMap(sampleDocument, collectionRepository, embeddingRepository);

            assertEquals(1L, result.get("id"));
            assertEquals("Test Document", result.get("title"));
            assertEquals(3L, result.get("chunkCount"));
            assertNull(result.get("collectionName")); // no collection set
        }
    }

    @Nested
    @DisplayName("toListMap (preview variant)")
    class ListMapTests {

        @Test
        @DisplayName("includes contentPreview instead of full content")
        void includesContentPreview() {
            sampleDocument.setContent("A".repeat(500));
            Map<Long, String> emptyCollectionMap = new HashMap<>();
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(0L);

            Map<String, Object> result = DocumentMapper.toListMap(sampleDocument, emptyCollectionMap, embeddingRepository);

            assertEquals("A".repeat(200) + "...", result.get("contentPreview"));
            assertNull(result.get("content")); // full content not included
        }

        @Test
        @DisplayName("truncates at word boundary approximation")
        void truncatesLongContent() {
            sampleDocument.setContent("Short text");
            Map<Long, String> emptyCollectionMap = new HashMap<>();
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(0L);

            Map<String, Object> result = DocumentMapper.toListMap(sampleDocument, emptyCollectionMap, embeddingRepository);

            assertEquals("Short text", result.get("contentPreview"));
            assertNull(result.get("content"));
        }

        @Test
        @DisplayName("handles null content gracefully")
        void handlesNullContent() {
            sampleDocument.setContent(null);
            Map<Long, String> emptyCollectionMap = new HashMap<>();
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(0L);

            Map<String, Object> result = DocumentMapper.toListMap(sampleDocument, emptyCollectionMap, embeddingRepository);

            assertNull(result.get("contentPreview"));
            assertNull(result.get("content"));
        }

        @Test
        @DisplayName("maps core fields correctly")
        void mapsCoreFieldsCorrectly() {
            sampleDocument.setContent("Preview content");
            sampleDocument.setCollectionId(5L);
            Map<Long, String> collectionMap = Map.of(5L, "Collection");
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(3L);

            Map<String, Object> result = DocumentMapper.toListMap(sampleDocument, collectionMap, embeddingRepository);

            assertEquals(1L, result.get("id"));
            assertEquals("Test Document", result.get("title"));
            assertEquals(3L, result.get("chunkCount"));
            assertEquals(5L, result.get("collectionId"));
            assertEquals("Collection", result.get("collectionName"));
            assertEquals("Preview content", result.get("contentPreview"));
        }

        @Test
        @DisplayName("does not include metadata when null")
        void omitsNullMetadata() {
            sampleDocument.setContent("Content");
            sampleDocument.setMetadata(null);
            Map<Long, String> emptyCollectionMap = new HashMap<>();
            when(embeddingRepository.countByDocumentId(1L)).thenReturn(0L);

            Map<String, Object> result = DocumentMapper.toListMap(sampleDocument, emptyCollectionMap, embeddingRepository);

            assertNull(result.get("metadata"));
            assertNull(result.get("content"));
        }
    }

    @Nested
    @DisplayName("truncate utility")
    class TruncateTests {

        @Test
        @DisplayName("returns null as-is")
        void returnsNullAsIs() {
            assertNull(DocumentMapper.truncate(null, 200));
        }

        @Test
        @DisplayName("returns short text as-is")
        void returnsShortTextAsIs() {
            assertEquals("hello", DocumentMapper.truncate("hello", 200));
        }

        @Test
        @DisplayName("truncates and appends ellipsis")
        void truncatesAndAppendsEllipsis() {
            String longText = "A".repeat(300);
            String result = DocumentMapper.truncate(longText, 200);
            assertEquals(203, result.length());
            assertTrue(result.endsWith("..."));
            assertEquals("A".repeat(200) + "...", result);
        }

        @Test
        @DisplayName("handles exactly max length")
        void handlesExactlyMaxLength() {
            String text = "A".repeat(200);
            assertEquals(text, DocumentMapper.truncate(text, 200));
        }
    }

    @Nested
    @DisplayName("toVersionResponse")
    class VersionMapTests {

        @Test
        @DisplayName("maps all version fields correctly")
        void mapsAllVersionFieldsCorrectly() {
            RagDocumentVersion version = new RagDocumentVersion();
            version.setId(100L);
            version.setDocumentId(1L);
            version.setVersionNumber(2);
            version.setContentHash("hash-v2");
            version.setSize(2048L);
            version.setChangeType("UPDATE");
            version.setChangeDescription("Updated content");
            version.setCreatedAt(LocalDateTime.now());
            version.setContentSnapshot("Full content at this version");

            DocumentVersionResponse result = DocumentMapper.toVersionResponse(version);

            assertEquals(100L, result.id());
            assertEquals(1L, result.documentId());
            assertEquals(2, result.versionNumber());
            assertEquals("hash-v2", result.contentHash());
            assertEquals(2048L, result.size());
            assertEquals("UPDATE", result.changeType());
            assertEquals("Updated content", result.changeDescription());
            assertEquals("Full content at this version", result.contentSnapshot());
        }

        @Test
        @DisplayName("omits null content snapshot")
        void omitsNullContentSnapshot() {
            RagDocumentVersion version = new RagDocumentVersion();
            version.setId(101L);
            version.setDocumentId(1L);
            version.setVersionNumber(3);
            version.setContentHash("hash-v3");
            version.setSize(512L);
            version.setChangeType("CREATE");
            version.setChangeDescription("Initial version");
            version.setCreatedAt(LocalDateTime.now());
            version.setContentSnapshot(null);

            DocumentVersionResponse result = DocumentMapper.toVersionResponse(version);

            assertNull(result.contentSnapshot());
        }
    }

    @Nested
    @DisplayName("null input validation")
    class NullInputValidationTests {

        @Test
        @DisplayName("toListMap throws IllegalArgumentException for null document")
        void toListMap_throwsOnNullDocument() {
            assertThrows(IllegalArgumentException.class,
                    () -> DocumentMapper.toListMap(null, new HashMap<>(), embeddingRepository));
        }

        @Test
        @DisplayName("toMap (batch) throws IllegalArgumentException for null document")
        void toMapBatch_throwsOnNullDocument() {
            assertThrows(IllegalArgumentException.class,
                    () -> DocumentMapper.toMap((RagDocument) null, new HashMap<>(), embeddingRepository));
        }

        @Test
        @DisplayName("toMap (single) throws IllegalArgumentException for null document")
        void toMapSingle_throwsOnNullDocument() {
            assertThrows(IllegalArgumentException.class,
                    () -> DocumentMapper.toMap(null, collectionRepository, embeddingRepository));
        }

        @Test
        @DisplayName("toVersionResponse throws IllegalArgumentException for null version")
        void toVersionResponse_throwsOnNullVersion() {
            assertThrows(IllegalArgumentException.class,
                    () -> DocumentMapper.toVersionResponse(null));
        }

        @Test
        @DisplayName("toSummary throws IllegalArgumentException for null document")
        void toSummary_throwsOnNullDocument() {
            assertThrows(IllegalArgumentException.class,
                    () -> DocumentMapper.toSummary(null, new HashMap<>(), embeddingRepository));
        }

        @Test
        @DisplayName("toDetailResponse throws IllegalArgumentException for null document")
        void toDetailResponse_throwsOnNullDocument() {
            assertThrows(IllegalArgumentException.class,
                    () -> DocumentMapper.toDetailResponse(null, new HashMap<>(), embeddingRepository));
        }
    }
}
