package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentVersionService — Document Version History Service")
class DocumentVersionServiceTest {

    @Mock
    private RagDocumentVersionRepository versionRepository;

    @InjectMocks
    private DocumentVersionService versionService;

    private RagDocument sampleDoc;

    @BeforeEach
    void setUp() {
        sampleDoc = new RagDocument();
        sampleDoc.setId(1L);
        sampleDoc.setTitle("测试文档");
        sampleDoc.setContent("这是测试内容");
        sampleDoc.setContentHash("abc123hash");
        sampleDoc.setSize(100L);
        sampleDoc.setMetadata(Map.of("key", "value"));
    }

    @Nested
    @DisplayName("recordVersion — record version")
    class RecordVersion {

        @Test
        @DisplayName("records new version")
        void recordsNewVersion() {
            when(versionRepository.findByDocumentIdAndContentHash(1L, "abc123hash"))
                    .thenReturn(List.of());
            when(versionRepository.findLatestByDocumentId(1L))
                    .thenReturn(Optional.empty());
            when(versionRepository.save(any(RagDocumentVersion.class)))
                    .thenAnswer(inv -> {
                        RagDocumentVersion v = inv.getArgument(0);
                        v.setId(100L);
                        return v;
                    });

            Optional<RagDocumentVersion> result = versionService.recordVersion(sampleDoc, "CREATE", "初始版本");

            assertTrue(result.isPresent());
            assertEquals(1, result.get().getVersionNumber());
            assertEquals("abc123hash", result.get().getContentHash());
            assertEquals("CREATE", result.get().getChangeType());
            assertEquals("这是测试内容", result.get().getContentSnapshot());

            verify(versionRepository).save(any(RagDocumentVersion.class));
        }

        @Test
        @DisplayName("version number increments")
        void versionNumberIncrements() {
            RagDocumentVersion latestVersion = new RagDocumentVersion();
            latestVersion.setVersionNumber(3);

            when(versionRepository.findByDocumentIdAndContentHash(1L, "abc123hash"))
                    .thenReturn(List.of());
            when(versionRepository.findLatestByDocumentId(1L))
                    .thenReturn(Optional.of(latestVersion));
            when(versionRepository.save(any(RagDocumentVersion.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            Optional<RagDocumentVersion> result = versionService.recordVersion(sampleDoc, "UPDATE", "内容更新");

            assertTrue(result.isPresent());
            assertEquals(4, result.get().getVersionNumber());
        }

        @Test
        @DisplayName("skips duplicate hash")
        void skipsDuplicateHash() {
            RagDocumentVersion existing = new RagDocumentVersion();
            existing.setContentHash("abc123hash");

            when(versionRepository.findByDocumentIdAndContentHash(1L, "abc123hash"))
                    .thenReturn(List.of(existing));

            Optional<RagDocumentVersion> result = versionService.recordVersion(sampleDoc, "UPDATE", "更新");

            assertTrue(result.isEmpty());
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips when no document ID")
        void skipsWhenNoId() {
            sampleDoc.setId(null);

            Optional<RagDocumentVersion> result = versionService.recordVersion(sampleDoc, "CREATE", "创建");

            assertTrue(result.isEmpty());
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips when no contentHash")
        void skipsWhenNoHash() {
            sampleDoc.setContentHash(null);

            Optional<RagDocumentVersion> result = versionService.recordVersion(sampleDoc, "CREATE", "创建");

            assertTrue(result.isEmpty());
            verify(versionRepository, never()).save(any());
        }

        @Test
        @DisplayName("snapshot includes metadata")
        void snapshotIncludesMetadata() {
            when(versionRepository.findByDocumentIdAndContentHash(1L, "abc123hash"))
                    .thenReturn(List.of());
            when(versionRepository.findLatestByDocumentId(1L))
                    .thenReturn(Optional.empty());
            when(versionRepository.save(any(RagDocumentVersion.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            Optional<RagDocumentVersion> result = versionService.recordVersion(sampleDoc, "CREATE", null);

            assertTrue(result.isPresent());
            assertEquals(Map.of("key", "value"), result.get().getMetadataSnapshot());
            assertEquals(100L, result.get().getSize());
        }
    }

    @Nested
    @DisplayName("forceRecordVersion — force record version")
    class ForceRecordVersion {

        @Test
        @DisplayName("forces record even with duplicate hash")
        void forcesRecordEvenWithDuplicateHash() {
            RagDocumentVersion existing = new RagDocumentVersion();
            existing.setContentHash("abc123hash");

            // Force record even when hash already exists
            when(versionRepository.findLatestByDocumentId(1L))
                    .thenReturn(Optional.empty());
            when(versionRepository.save(any(RagDocumentVersion.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            RagDocumentVersion result = versionService.forceRecordVersion(sampleDoc, "EMBED", "首次嵌入");

            assertNotNull(result);
            assertEquals(1, result.getVersionNumber());
            assertEquals("EMBED", result.getChangeType());
        }
    }

    @Nested
    @DisplayName("Version Query")
    class QueryVersions {

        @Test
        @DisplayName("paginated version history")
        void getVersionHistory() {
            Page<RagDocumentVersion> page = new PageImpl<>(List.of(new RagDocumentVersion()));
            when(versionRepository.findByDocumentIdOrderByVersionNumberDesc(eq(1L), any(PageRequest.class)))
                    .thenReturn(page);

            Page<RagDocumentVersion> result = versionService.getVersionHistory(1L, PageRequest.of(0, 10));

            assertEquals(1, result.getTotalElements());
        }

        @Test
        @DisplayName("full version history (ascending)")
        void getFullVersionHistory() {
            RagDocumentVersion v1 = new RagDocumentVersion();
            v1.setVersionNumber(1);
            RagDocumentVersion v2 = new RagDocumentVersion();
            v2.setVersionNumber(2);

            when(versionRepository.findByDocumentIdOrderByVersionNumberAsc(1L))
                    .thenReturn(List.of(v1, v2));

            List<RagDocumentVersion> result = versionService.getFullVersionHistory(1L);

            assertEquals(2, result.size());
            assertEquals(1, result.get(0).getVersionNumber());
            assertEquals(2, result.get(1).getVersionNumber());
        }

        @Test
        @DisplayName("gets specific version")
        void getSpecificVersion() {
            RagDocumentVersion v = new RagDocumentVersion();
            v.setVersionNumber(2);

            when(versionRepository.findByDocumentIdAndVersionNumber(1L, 2))
                    .thenReturn(Optional.of(v));

            Optional<RagDocumentVersion> result = versionService.getVersion(1L, 2);

            assertTrue(result.isPresent());
            assertEquals(2, result.get().getVersionNumber());
        }

        @Test
        @DisplayName("gets latest version")
        void getLatestVersion() {
            RagDocumentVersion v = new RagDocumentVersion();
            v.setVersionNumber(5);

            when(versionRepository.findLatestByDocumentId(1L))
                    .thenReturn(Optional.of(v));

            Optional<RagDocumentVersion> result = versionService.getLatestVersion(1L);

            assertTrue(result.isPresent());
            assertEquals(5, result.get().getVersionNumber());
        }

        @Test
        @DisplayName("counts versions")
        void countVersions() {
            when(versionRepository.countByDocumentId(1L)).thenReturn(5L);

            assertEquals(5L, versionService.countVersions(1L));
        }
    }

    @Nested
    @DisplayName("Version Delete")
    class DeleteVersions {

        @Test
        @DisplayName("deletes all versions of document")
        void deleteAllVersions() {
            versionService.deleteVersions(1L);

            verify(versionRepository).deleteByDocumentId(1L);
        }
    }
}
