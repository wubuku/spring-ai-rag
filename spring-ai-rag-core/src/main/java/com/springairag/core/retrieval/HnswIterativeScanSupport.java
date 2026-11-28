package com.springairag.core.retrieval;

import com.springairag.core.properties.RagRetrievalProperties;
import com.springairag.core.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * pgvector 0.8+ {@code hnsw.iterative_scan} 运行时能力探测。
 *
 * <p>探测只查询一次 {@code pg_settings}：结果（支持/不支持）缓存在实例内，
 * 不会随每次查询重复访问系统表。数据库不可达、权限不足或旧版 pgvector 没有该 GUC 时，
 * 统一降级为“不支持”，只记录一次 WARN，绝不阻断启动或查询。</p>
 */
@Component
public class HnswIterativeScanSupport {

    private static final Logger log = LoggerFactory.getLogger(HnswIterativeScanSupport.class);
    private static final String PROBE_SQL =
            "SELECT name FROM pg_settings "
                    + "WHERE name IN ('hnsw.iterative_scan', 'hnsw.max_scan_tuples')";

    private final JdbcTemplate jdbcTemplate;
    private final RagRetrievalProperties.HnswIterativeScan mode;
    private final AtomicReference<Boolean> supported = new AtomicReference<>();

    /**
     * Spring 装配构造器。{@code JdbcTemplate} 在某些无数据源上下文（如契约测试）缺失，
     * 此时降级为不启用 HNSW GUC，而不是阻断启动。
     */
    @Autowired
    public HnswIterativeScanSupport(
            @Autowired(required = false) JdbcTemplate jdbcTemplate,
            @Autowired(required = false) RagProperties properties) {
        this(jdbcTemplate, properties == null
                ? RagRetrievalProperties.HnswIterativeScan.RELAXED_ORDER
                : properties.getRetrieval().getHnswIterativeScan());
    }

    /** Spring 无参兜底构造器：仅供无 JdbcTemplate/无 RagProperties 的空上下文使用。 */
    public HnswIterativeScanSupport() {
        this(null, RagRetrievalProperties.HnswIterativeScan.OFF);
    }

    /** 测试用构造器：可显式覆盖模式，避免依赖外部 application.yml。 */
    public HnswIterativeScanSupport(JdbcTemplate jdbcTemplate,
                                    RagRetrievalProperties.HnswIterativeScan mode) {
        this.jdbcTemplate = jdbcTemplate;
        this.mode = mode == null ? RagRetrievalProperties.HnswIterativeScan.OFF : mode;
    }

    /**
     * 当前是否应在查询事务内设置 GUC。OFF 模式不做探测，直接返回 false。
     */
    public boolean enabled() {
        if (mode == RagRetrievalProperties.HnswIterativeScan.OFF) {
            return false;
        }
        if (jdbcTemplate == null) {
            return false;
        }
        Boolean cached = supported.get();
        if (cached != null) {
            return cached;
        }
        // 并发首次查询允许重复探测，但结果收敛后不再查询；探测失败只 WARN 一次。
        boolean detected = probe();
        supported.compareAndSet(null, detected);
        return supported.get();
    }

    private boolean probe() {
        try {
            List<String> names = jdbcTemplate.queryForList(PROBE_SQL, String.class);
            boolean available = names.contains("hnsw.iterative_scan")
                    && names.contains("hnsw.max_scan_tuples");
            if (!available) {
                log.warn("pgvector hnsw.iterative_scan 不可用（旧版 pgvector 或未加载扩展），"
                        + "向量检索降级为不设置 HNSW 迭代扫描 GUC；不影响查询可用性");
            }
            return available;
        } catch (DataAccessException e) {
            log.warn("探测 pgvector hnsw.iterative_scan 失败，向量检索降级为不设置 "
                    + "HNSW 迭代扫描 GUC：{}", e.getMessage());
            return false;
        }
    }

    /** 白名单枚举映射到 pgvector 接受的固定字面量。 */
    public String gucValue() {
        return mode.gucValue();
    }

    /** 供测试确认探测 SQL 形状。 */
    static String probeSql() {
        return PROBE_SQL;
    }

    /** 暴露给确定性单测：重置探测缓存，模拟服务重启。 */
    void resetCacheForTest() {
        supported.set(null);
    }
}
