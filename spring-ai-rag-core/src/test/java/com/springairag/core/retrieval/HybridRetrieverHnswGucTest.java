package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.config.RagProperties;
import com.springairag.core.properties.RagRetrievalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V-RAG-1：带 collection / payloadContains / tenant 过滤的向量 SQL 在事务内设置
 * HNSW GUC；未开启或旧版 pgvector 时 no-op；SET LOCAL 随事务结束自动复位。
 */
class HybridRetrieverHnswGucTest {

    private JdbcTemplate jdbc;
    private EmbeddingModel embeddingModel;
    private RagProperties props;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        embeddingModel = mock(EmbeddingModel.class);
        float[] vector = new float[1024];
        Arrays.fill(vector, 0.1f);
        when(embeddingModel.embed(anyString())).thenReturn(vector);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> List.of());
        props = new RagProperties();
    }

    /** 记录真实事务边界（begin/commit/rollback），以验证 SET LOCAL 不逃逸事务。 */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final List<String> events = new ArrayList<>();

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected Object doGetTransaction() {
            return new SimpleTransactionStatus();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return false;
        }
    }

    private HybridRetrieverService service(HnswIterativeScanSupport support,
                                           PlatformTransactionManager manager) {
        var service = new HybridRetrieverService(
                embeddingModel, jdbc, props, null, (executor -> executor.run()));
        service.configureHnswGuc(support,
                manager == null ? null : new TransactionTemplate(manager));
        return service;
    }

    private HnswIterativeScanSupport supported(RagRetrievalProperties.HnswIterativeScan mode) {
        when(jdbc.queryForList(HnswIterativeScanSupport.probeSql(), String.class))
                .thenReturn(List.of("hnsw.iterative_scan", "hnsw.max_scan_tuples"));
        return new HnswIterativeScanSupport(jdbc, mode);
    }

    @Test
    void filteredVectorQuerySetsWhitelistedGucInsideTransaction() {
        var manager = new RecordingTransactionManager();
        var service = service(
                supported(RagRetrievalProperties.HnswIterativeScan.RELAXED_ORDER), manager);
        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(5).useHybridSearch(false).build();

        service.searchInScope("倾斜租户查询",
                RetrievalScope.selectedCollections(List.of(42L), List.of(), "json-record"),
                List.of(), 5, config,
                new JsonbContainmentFilter("{\"tenantKey\":\"tenant-small\"}"));

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
        order.verify(jdbc).execute("SET LOCAL hnsw.max_scan_tuples = 20000");
        order.verify(jdbc).queryForList(anyString(), any(Object[].class));
        assertEquals(List.of("begin", "commit"), manager.events);
    }

    @Test
    void strictOrderWhitelistAndCustomTupleBoundAreApplied() {
        props.getRetrieval().setHnswIterativeScan(
                RagRetrievalProperties.HnswIterativeScan.STRICT_ORDER);
        props.getRetrieval().setHnswMaxScanTuples(50_000);
        var manager = new RecordingTransactionManager();
        var service = service(
                supported(RagRetrievalProperties.HnswIterativeScan.STRICT_ORDER), manager);
        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(5).useHybridSearch(false).build();

        service.searchInScope("排序模式", RetrievalScope.selectedCollections(
                        List.of(42L), List.of(), "json-record"),
                List.of(), 5, config,
                new JsonbContainmentFilter("{\"tenantKey\":\"tenant-small\"}"));

        verify(jdbc).execute("SET LOCAL hnsw.iterative_scan = strict_order");
        verify(jdbc).execute("SET LOCAL hnsw.max_scan_tuples = 50000");
    }

    @Test
    void unsupportedPgvectorNeverSetsGucAndStillReturnsRows() {
        when(jdbc.queryForList(HnswIterativeScanSupport.probeSql(), String.class))
                .thenReturn(List.of());
        var manager = new RecordingTransactionManager();
        var service = service(
                new HnswIterativeScanSupport(jdbc,
                        RagRetrievalProperties.HnswIterativeScan.RELAXED_ORDER), manager);
        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(5).useHybridSearch(false).build();

        service.searchInScope("旧版降级", RetrievalScope.selectedCollections(
                        List.of(42L), List.of(), "json-record"),
                List.of(), 5, config,
                new JsonbContainmentFilter("{\"tenantKey\":\"tenant-small\"}"));

        verify(jdbc, times(0)).execute(anyString());
        assertTrue(manager.events.isEmpty(), "降级路径不应额外开启事务");
    }

    @Test
    void existingTransactionIsReusedWithoutOpeningNestedTransaction() {
        var manager = new RecordingTransactionManager();
        var service = service(
                supported(RagRetrievalProperties.HnswIterativeScan.RELAXED_ORDER), manager);
        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(5).useHybridSearch(false).build();

        TransactionTemplate outer = new TransactionTemplate(manager);
        outer.executeWithoutResult(status -> service.searchInScope("已有事务",
                RetrievalScope.selectedCollections(List.of(42L), List.of(), "json-record"),
                List.of(), 5, config,
                new JsonbContainmentFilter("{\"tenantKey\":\"tenant-small\"}")));

        assertEquals(1, manager.events.stream().filter("begin"::equals).count());
        verify(jdbc).execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
        // 事务由调用方结束时才提交，SET LOCAL 随之复位，不污染连接池。
        assertTrue(manager.events.contains("commit"));
        assertTrue(!TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void unfilteredQueryDoesNotRequireTransactionOrGuc() {
        var manager = new RecordingTransactionManager();
        var service = service(
                supported(RagRetrievalProperties.HnswIterativeScan.RELAXED_ORDER), manager);
        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(5).useHybridSearch(false).build();

        service.searchInScope("无过滤", RetrievalScope.unscoped(),
                List.of(), 5, config);

        verify(jdbc, times(0)).execute(anyString());
        assertTrue(manager.events.isEmpty());
    }
}
