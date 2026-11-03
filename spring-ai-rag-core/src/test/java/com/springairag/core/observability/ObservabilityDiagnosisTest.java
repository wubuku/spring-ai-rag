package com.springairag.core.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.springairag.api.dto.FailureDetails;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.metrics.RagMetricsService;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.service.DocumentChunkingService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.EmbeddingPersistenceService;
import com.springairag.documents.chunk.TextChunk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 故障定位的 gated 断言：模拟上游 429 后，只靠同一条日志与指标
 * 是否足以回答「谁、哪个文档、哪个 profile、哪个上游、能否重试」。
 *
 * <p>这不是「组件能跑」的测试，而是「线上出事时查得出来」的测试：
 * 日志必须带 traceId / principalId / documentId / profile / reason，
 * 指标必须带 operation / provider / reason / retryable 有界标签。
 */
class ObservabilityDiagnosisTest {

    private static final String TRACE_ID = "req-embed-abc";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void upstream429IsLocatableFromOneLogLineAndMetricTags() {
        RagDocument document = new RagDocument();
        document.setId(42L);
        document.setContent("hello rag");
        document.setDocumentType("text");
        document.setContentHash("hash-42");
        document.setVersion(3L);

        RagDocumentRepository repository = mock(RagDocumentRepository.class);
        when(repository.findById(42L)).thenReturn(Optional.of(document));

        EmbeddingProfile profile = new EmbeddingProfile(
                7L, "bge-m3-1024", "siliconflow", "bge-m3", "rev-1",
                1024, "COSINE", "L2", true);
        EmbeddingProfileProvider profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(profile);

        DocumentChunkingService chunkingService = mock(DocumentChunkingService.class);
        when(chunkingService.chunkerVersionFor(document)).thenReturn("chunker-v1");
        DocumentChunkingService.PreparedChunks prepared =
                new DocumentChunkingService.PreparedChunks(
                        new com.springairag.core.service.DocumentDerivationDescriptorProvider
                                .Descriptor("text", "chunker-v1"),
                        List.of(new TextChunk("hello rag", 0, 9)));
        when(chunkingService.prepare(document)).thenReturn(prepared);

        EmbeddingPersistenceService persistenceService =
                mock(EmbeddingPersistenceService.class);
        when(persistenceService.findCacheState(
                eq(42L), eq("text"), eq(profile), eq("hash-42")))
                .thenReturn(new EmbeddingPersistenceService.CacheState(false, 0));

        EmbeddingBatchService batchService = mock(EmbeddingBatchService.class);
        when(batchService.createEmbeddingsBatchForProfile(any(), any()))
                .thenThrow(rateLimited());

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagMetricsService metrics = new RagMetricsService(registry);
        DocumentEmbedService service = new DocumentEmbedService(
                repository, batchService, persistenceService, profileProvider,
                new RagProperties(), chunkingService);
        service.setMetricsService(metrics);

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(DocumentEmbedService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        MDC.put("traceId", TRACE_ID);
        MDC.put("principalId", "db:99");

        Map<String, Object> result;
        try {
            result = service.embedDocument(42L);
        } finally {
            serviceLogger.detachAppender(appender);
            appender.stop();
        }

        assertEquals("FAILED", result.get("status"));

        // 1) 指标：固定标签即可定位到失败类型与上游。
        FailureDetails details = (FailureDetails) result.get("failureDetails");
        assertNotNull(details);
        assertEquals("UPSTREAM", details.origin());
        assertEquals("UPSTREAM_RATE_LIMITED", details.reason());
        assertTrue(details.retryable());

        var counter = registry.find("rag.upstream.failures")
                .tags("operation", "embedding.document",
                        "provider", "siliconflow",
                        "reason", "UPSTREAM_RATE_LIMITED",
                        "retryable", "true")
                .counter();
        assertNotNull(counter, "指标必须能按 operation/provider/reason 过滤到这一次失败");
        assertEquals(1.0, counter.count());

        // 2) 日志：一条 WARN 就能回答谁、哪个文档、哪个 profile、哪个上游、能否重试。
        ILoggingEvent failureLog = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage()
                        .contains("operation=embedding.document"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "缺少可定位的上游失败日志：" + appender.list));
        String message = failureLog.getFormattedMessage();
        assertTrue(message.contains("provider=siliconflow"), message);
        assertTrue(message.contains("profile=bge-m3-1024"), message);
        assertTrue(message.contains("documentId=42"), message);
        assertTrue(message.contains("reason=UPSTREAM_RATE_LIMITED"), message);
        assertTrue(message.contains("retryable=true"), message);

        assertEquals(TRACE_ID, failureLog.getMDCPropertyMap().get("traceId"),
                "失败日志必须能按 traceId 串回请求");
        assertEquals("db:99", failureLog.getMDCPropertyMap().get("principalId"),
                "失败日志必须能回答是谁发起的");
    }

    @Test
    void providerTimeoutKeepsSameTraceAcrossDependencyPool() throws Exception {
        // 依赖调用线程池是 traceId 最容易断掉的地方：provider 日志必须仍在原 trace 下。
        var seen = new AtomicReference<String>();
        com.springairag.core.config.RagProperties properties = new RagProperties();
        var registry = new SimpleMeterRegistry();
        var admission = new com.springairag.core.admission.AdmissionService(properties, registry);
        MDC.put("traceId", TRACE_ID);
        try (var executor = new com.springairag.core.admission.DependencyCallExecutor(
                admission, properties, registry)) {
            try {
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.springairag.core.admission.DependencyCallException.class,
                        () -> executor.execute("embedding", () -> {
                            seen.set(MDC.get("traceId"));
                            throw new com.springairag.core.admission.DependencyCallException(
                                    "DEPENDENCY_TIMEOUT");
                        }));
            } finally {
                MDC.clear();
            }
        }
        assertEquals(TRACE_ID, seen.get(),
                "依赖线程池内必须能看到提交方的 traceId");
    }

    private static RestClientResponseException rateLimited() {
        return new RestClientResponseException(
                "429 Too Many Requests", 429, "Too Many Requests",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }
}
