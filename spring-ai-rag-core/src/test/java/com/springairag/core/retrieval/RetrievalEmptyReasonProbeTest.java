package com.springairag.core.retrieval;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖空结果诊断探针：不可用/不匹配短路、行映射、执行失败与超时
 * 的降级路径（probeFailed）。
 */
class RetrievalEmptyReasonProbeTest {

    private JdbcTemplate jdbcTemplate;
    private DocumentDerivationDescriptorProvider descriptorProvider;
    private RetrievalEmptyReasonProbe probe;
    private EmbeddingProfile profile;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        descriptorProvider = mock(DocumentDerivationDescriptorProvider.class);
        probe = new RetrievalEmptyReasonProbe(jdbcTemplate, descriptorProvider);
        profile = new EmbeddingProfile(7L, "bge-m3", "siliconflow", "BAAI/bge-m3",
                "r1", 1024, "COSINE", "normalized", true);
        when(descriptorProvider.jsonRecordDescriptor())
                .thenReturn(new DocumentDerivationDescriptorProvider.Descriptor(
                        "json-record", "jr-v1"));
        when(descriptorProvider.textDescriptor())
                .thenReturn(new DocumentDerivationDescriptorProvider.Descriptor(
                        "text", "t-v1"));
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any(Object[].class))).thenAnswer(invocation -> {
                    org.springframework.jdbc.core.ResultSetExtractor<?> extractor =
                            invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getInt("enabled_docs")).thenReturn(10);
                    when(rs.getInt("fresh_docs")).thenReturn(2);
                    return extractor.extractData(rs);
                });
    }

    private RetrievalScope scope(boolean matchNone) {
        return new RetrievalScope(
                RetrievalScope.CollectionFilter.NONE,
                List.of(), List.of(),
                null, matchNone);
    }

    @Test
    void unavailableWhenScopeMatchNoneOrProfileMissing() {
        // unavailable 表示无法判定（available=false），并非探针失败。
        var unavailable = RetrievalEmptyReasonProbe.Eligibility.unavailable();
        assertFalse(unavailable.available());
        assertFalse(unavailable.failed());

        var result = probe.count(scope(true), null, profile, 1_000);
        assertFalse(result.available());

        var result2 = probe.count(scope(false), null, null, 1_000);
        assertFalse(result2.available());
    }

    @Test
    void mapsEnabledAndFreshDocumentCounts() {
        var eligibility = probe.count(scope(false), null, profile, 1_000);

        assertTrue(eligibility.available());
        assertFalse(eligibility.failed());
        assertEquals(10, eligibility.enabledDocuments());
        assertEquals(2, eligibility.freshDocuments());
    }

    @Test
    void degradesToProbeFailedWhenQueryThrows() {
        when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any(Object[].class))).thenThrow(new RuntimeException("db down"));

        var eligibility = probe.count(scope(false), null, profile, 1_000);

        assertTrue(eligibility.failed());
        assertFalse(eligibility.available());
    }

    @Test
    void degradesToProbeFailedOnTimeout() {
        // 计数查询阻塞超过超时窗口（最小 100ms）→ future 超时取消。
        when(jdbcTemplate.query(anyString(),
                any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    TimeUnit.MILLISECONDS.sleep(500);
                    return null;
                });

        var eligibility = probe.count(scope(false), null, profile, 100);

        assertTrue(eligibility.failed());
        assertFalse(eligibility.available());
    }

    @Test
    void probeFailedFactoryHasFailureMarker() {
        var failed = RetrievalEmptyReasonProbe.Eligibility.probeFailed();
        assertTrue(failed.failed());
        assertFalse(failed.available());
    }
}
