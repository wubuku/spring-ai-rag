package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DerivationIntegrityRepository 扫描与分页长尾（Batch 655，JaCoCo
 * 驱动）：inspect(RagDocument) 委托、scanCollection 携带 bucket 谓
 * 词的 classifiedIds 链、无 bucket 时选择谓词短路、分类结果为空时
 * 的空快照列表。
 */
class DerivationIntegrityRepositoryScanPagingTailTest {

    private JdbcTemplate jdbcTemplate;
    private DerivationIntegrityRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        var profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                7L, "bge-m3", "vendor", "bge-m3", "rev-1", 1024,
                "cosine", "normalize", true));
        repository = new DerivationIntegrityRepository(
                jdbcTemplate,
                profileProvider,
                new DocumentDerivationDescriptorProvider(new RagProperties()));
    }

    private RagDocument document() {
        RagDocument doc = new RagDocument();
        doc.setId(1L);
        doc.setEnabled(true);
        return doc;
    }

    @SuppressWarnings("unchecked")
    private void stubQueryMappingFullRow() throws Exception {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getString(anyString())).thenReturn(null);
        when(rs.getObject(anyString())).thenReturn(null);
        when(rs.getInt(anyString())).thenReturn(0);
        when(rs.getLong(anyString())).thenReturn(0L);
        when(rs.getBoolean(anyString())).thenReturn(false);
        when(rs.getLong("id")).thenReturn(1L);
        when(rs.getString("title")).thenReturn("Doc A");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @Test
    void inspectDocumentDelegatesToIdLookup() throws Exception {
        stubQueryMappingFullRow();

        DerivationIntegrityRepository.Snapshot snapshot =
                repository.inspect(document());

        assertEquals(1L, snapshot.documentId());
        assertEquals("Doc A", snapshot.title());
    }

    @Test
    void scanCollectionWithBucketRunsClassifiedIdsAndInspection()
            throws Exception {
        stubQueryMappingFullRow();
        when(jdbcTemplate.queryForList(
                anyString(), org.mockito.ArgumentMatchers.eq(Long.class),
                any(Object[].class)))
                .thenReturn(List.of(1L));

        List<DerivationIntegrityRepository.Snapshot> snapshots =
                repository.scanCollection(10L, "READY", 5, 20);

        assertEquals(1, snapshots.size());
        assertEquals(1L, snapshots.getFirst().documentId());
    }

    @Test
    void scanCollectionWithoutBucketSkipsSelectionPredicatesAndYieldsEmpty() {
        when(jdbcTemplate.queryForList(
                anyString(), org.mockito.ArgumentMatchers.eq(Long.class),
                any(Object[].class)))
                .thenReturn(List.of());

        List<DerivationIntegrityRepository.Snapshot> snapshots =
                repository.scanCollection(10L, null, 0, 10);

        assertTrue(snapshots.isEmpty());
    }
}
