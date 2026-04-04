package com.springairag.core.controller;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.HybridRetrieverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagSearchController 性能基准测试
 *
 * <p>验证成功标准：支持 100 并发请求
 * <ul>
 *   <li>100 个并发搜索请求全部成功</li>
 *   <li>总耗时在合理范围内（< 10s，取决于 mock 响应速度）</li>
 * </ul>
 */
class RagSearchControllerBenchmarkTest {

    private HybridRetrieverService hybridRetriever;
    private RagSearchController controller;

    @BeforeEach
    void setUp() {
        hybridRetriever = mock(HybridRetrieverService.class);
        controller = new RagSearchController(hybridRetriever);

        RetrievalResult mockResult = new RetrievalResult();
        mockResult.setDocumentId("doc-benchmark");
        mockResult.setChunkText("Benchmark test content");
        mockResult.setScore(0.95);

        when(hybridRetriever.search(anyString(), isNull(), isNull(), anyInt(), any(RetrievalConfig.class)))
                .thenReturn(List.of(mockResult));
    }

    @Test
    @DisplayName("100 并发搜索请求 - 验证并发处理能力")
    void search_concurrentRequests_allSucceed() throws Exception {
        int threadCount = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    startLatch.await(); // 等待所有线程同时开始
                    ResponseEntity<?>  response = controller.search(
                            "concurrent query " + index,
                            10,
                            true,
                            0.5,
                            0.5
                    );
                    if (response.getStatusCode().is2xxSuccessful()) {
                        successCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 释放所有线程同时开始
        startLatch.countDown();

        // 等待所有请求完成（最多 30 秒）
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(completed, "所有请求应在 30 秒内完成");
        assertEquals(threadCount, successCount.get(),
                "100 个并发请求应全部成功，实际成功: " + successCount.get() + ", 失败: " + errorCount.get());
        assertEquals(0, errorCount.get(), "不应有任何失败请求");
    }

    @Test
    @DisplayName("50 并发搜索请求吞吐量 - 验证每批 < 1s")
    void search_concurrent50_throughputUnder1s() throws Exception {
        int threadCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    ResponseEntity<?>  response = controller.search(
                            "throughput query " + System.currentTimeMillis(),
                            10, true, 0.5, 0.5
                    );
                    if (response.getStatusCode().is2xxSuccessful()) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long startTime = System.currentTimeMillis();
        startLatch.countDown();

        // 等待最多 5 秒
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;
        pool.shutdown();

        assertTrue(completed, "50 个并发请求应在 5 秒内完成");
        assertEquals(threadCount, successCount.get(),
                "50 个请求应全部成功，实际: " + successCount.get());
        // 验证吞吐量：50 请求在 1 秒内完成 = 50+ ops/s
        assertTrue(elapsed < 1000,
                "50 个并发请求应在 1 秒内完成，实际: " + elapsed + "ms");
    }
}
