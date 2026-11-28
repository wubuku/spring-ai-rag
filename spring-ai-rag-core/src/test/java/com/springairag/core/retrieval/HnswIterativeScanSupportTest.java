package com.springairag.core.retrieval;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.springairag.core.properties.RagRetrievalProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import com.springairag.core.config.RagProperties;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * pgvector 能力探测：缓存、旧版降级与 OFF 一键关闭。
 */
class HnswIterativeScanSupportTest {

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            ((Logger) LoggerFactory.getLogger(HnswIterativeScanSupport.class))
                    .detachAppender(appender);
            appender = null;
        }
    }

    private ListAppender<ILoggingEvent> captureWarnLogs() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(HnswIterativeScanSupport.class))
                .addAppender(appender);
        return appender;
    }

    private long warnCount(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("hnsw.iterative_scan"))
                .count();
    }

    @Test
    void supportedSettingIsCachedAcrossQueries() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(HnswIterativeScanSupport.probeSql(), String.class))
                .thenReturn(List.of("hnsw.iterative_scan", "hnsw.max_scan_tuples"));
        var support = new HnswIterativeScanSupport(jdbc,
                RagRetrievalProperties.HnswIterativeScan.RELAXED_ORDER);

        assertTrue(support.enabled());
        assertTrue(support.enabled());
        assertEquals("relaxed_order", support.gucValue());
        verify(jdbc, times(1)).queryForList(HnswIterativeScanSupport.probeSql(), String.class);
    }

    @Test
    void oldPgvectorWithoutGucDegradesWithSingleWarn() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(HnswIterativeScanSupport.probeSql(), String.class))
                .thenReturn(List.of());
        var logs = captureWarnLogs();
        var support = new HnswIterativeScanSupport(jdbc,
                RagRetrievalProperties.HnswIterativeScan.RELAXED_ORDER);

        assertFalse(support.enabled());
        assertFalse(support.enabled());
        assertFalse(support.enabled());
        assertEquals(1L, warnCount(logs));
        verify(jdbc, times(1)).queryForList(HnswIterativeScanSupport.probeSql(), String.class);
    }

    @Test
    void probeFailureNeverBreaksRetrievalAndWarnsOnce() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(HnswIterativeScanSupport.probeSql(), String.class))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        var logs = captureWarnLogs();
        var support = new HnswIterativeScanSupport(jdbc,
                RagRetrievalProperties.HnswIterativeScan.STRICT_ORDER);

        assertFalse(support.enabled());
        assertFalse(support.enabled());
        assertEquals(1L, warnCount(logs));
    }

    @Test
    void offModeSkipsProbeEntirely() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var support = new HnswIterativeScanSupport(jdbc,
                RagRetrievalProperties.HnswIterativeScan.OFF);

        assertFalse(support.enabled());
        assertEquals("off", support.gucValue());
        verify(jdbc, times(0)).queryForList(HnswIterativeScanSupport.probeSql(), String.class);
    }

    @Test
    void configDefaultsAndEnvParserUseWhitelist() {
        var props = new RagRetrievalProperties();
        assertEquals(RagRetrievalProperties.HnswIterativeScan.RELAXED_ORDER,
                props.getHnswIterativeScan());
        assertEquals(20_000, props.getHnswMaxScanTuples());
        assertEquals(RagRetrievalProperties.HnswIterativeScan.OFF,
                RagRetrievalProperties.HnswIterativeScan.parse("off"));
        assertEquals(RagRetrievalProperties.HnswIterativeScan.STRICT_ORDER,
                RagRetrievalProperties.HnswIterativeScan.parse("STRICT_ORDER"));
        assertEquals(RagRetrievalProperties.HnswIterativeScan.RELAXED_ORDER,
                RagRetrievalProperties.HnswIterativeScan.parse("injected'; DROP TABLE--"));
    }

    @Test
    void envStylePropertyOverridesWhitelistAndTupleBound() {
        // Spring Boot relaxed binding 把 RAG_RETRIEVAL_HNSW_ITERATIVE_SCAN=OFF
        // 映射为 rag.retrieval.hnsw-iterative-scan=OFF（无需修改 application.yml）。
        var source = new MapConfigurationPropertySource(Map.of(
                "rag.retrieval.hnsw-iterative-scan", "OFF",
                "rag.retrieval.hnsw-max-scan-tuples", "30000"));
        RagProperties props = new Binder(source)
                .bind("rag", Bindable.of(RagProperties.class)).get();
        assertEquals(RagRetrievalProperties.HnswIterativeScan.OFF,
                props.getRetrieval().getHnswIterativeScan());
        assertEquals(30_000, props.getRetrieval().getHnswMaxScanTuples());
    }
}
