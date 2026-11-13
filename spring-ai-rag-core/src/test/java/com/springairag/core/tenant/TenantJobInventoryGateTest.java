package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP7：后台任务现场清单静态校验（实施规划 §6 WP7 / §7「后台任务」行）。
 *
 * <p>以现场清点为准（不写死数量）：</p>
 * <ul>
 *   <li>所有含 {@code @Scheduled} / {@code @Async} / {@code TransactionSynchronization}
 *       的 core 生产文件必须在 {@link TenantTaskInventory} 中登记；</li>
 *   <li>租户私有任务必须标注 {@link TenantScopedJob}，并经
 *       {@link TenantJobDispatcher} / 统一 decorator 收敛；</li>
 *   <li>平台全局豁免必须写明理由；</li>
 *   <li>after-commit 文件必须显式捕获/传播 {@link TenantContext}。</li>
 * </ul>
 *
 * <p>本测试保证「新增任务文件但没登记」会红，避免日后重新做规模探索。</p>
 */
@DisplayName("TenantJobInventoryGate (后台任务现场清单)")
class TenantJobInventoryGateTest {

    private static final Path SRC =
            Path.of("src/main/java/com/springairag/core");
    private static final Path TEST_SRC =
            Path.of("src/test/java/com/springairag/core");

    private static List<Path> sourceFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(SRC)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String relative(Path p) {
        return SRC.relativize(p).toString().replace('\\', '/');
    }

    /** 真实注解/用法匹配（行首注解，避免 Javadoc 提及造成误判）。 */
    private static final java.util.regex.Pattern SCHEDULED =
            java.util.regex.Pattern.compile("(?m)^\\s*@Scheduled\\b");
    private static final java.util.regex.Pattern ASYNC =
            java.util.regex.Pattern.compile("(?m)^\\s*@Async\\b");
    private static final java.util.regex.Pattern AFTER_COMMIT =
            java.util.regex.Pattern.compile(
                    "TransactionSynchronizationManager|implements\\s+TransactionSynchronization");

    private static boolean isTaskFile(String text) {
        return SCHEDULED.matcher(text).find()
                || ASYNC.matcher(text).find()
                || AFTER_COMMIT.matcher(text).find();
    }

    private static List<String> filesWithAnyMarker(String... markers)
            throws IOException {
        return sourceFiles().stream()
                .filter(p -> {
                    try {
                        return isTaskFile(Files.readString(p));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .map(TenantJobInventoryGateTest::relative)
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("现场清点：@Scheduled / @Async / after-commit 全部登记，无幽灵/无漏项")
    void allTaskFilesRegistered() throws IOException {
        List<String> taskFiles = filesWithAnyMarker(
                "@Scheduled", "@Async", "TransactionSynchronization");
        assertFalse(taskFiles.isEmpty(), "现场必须有任务文件");

        Set<String> registered = new HashSet<>(TenantTaskInventory.allRegisteredPaths());
        Set<String> missing = new HashSet<>(taskFiles);
        missing.removeAll(registered);
        assertTrue(missing.isEmpty(),
                "未登记的后台任务文件（不得静默放过）：" + missing);

        Set<String> ghost = new HashSet<>(registered);
        ghost.removeAll(new HashSet<>(taskFiles));
        assertTrue(ghost.isEmpty(),
                "清单里的幽灵条目（现场已不存在）：" + ghost);

        // 基础设施文件（不含任务标记）单独验证存在，避免误判为幽灵。
        for (String path : TenantTaskInventory.INFRASTRUCTURE_FILES.keySet()) {
            assertTrue(Files.exists(SRC.resolve(path)),
                    "基础设施文件必须存在：" + path);
        }
    }

    @Test
    @DisplayName("租户私有任务文件标注 @TenantScopedJob")
    void tenantScopedFilesAnnotated() {
        for (String path : TenantTaskInventory.TENANT_SCOPED_FILES) {
            Path p = SRC.resolve(path);
            assertTrue(Files.exists(p), "清单文件不存在：" + path);
            try {
                assertTrue(Files.readString(p).contains("@TenantScopedJob"),
                        "租户私有任务必须标注 @TenantScopedJob：" + path);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    @DisplayName("平台全局豁免必须写明理由")
    void platformGlobalExemptionsDocumented() {
        for (var entry : TenantTaskInventory.PLATFORM_GLOBAL_FILES.entrySet()) {
            assertFalse(entry.getValue().isBlank(),
                    "平台全局豁免必须写明理由：" + entry.getKey());
        }
    }

    @Test
    @DisplayName("after-commit 三个文件捕获 TenantContext 并在回调传播")
    void afterCommitFilesPropagateTenantContext() {
        for (String path : TenantTaskInventory.AFTER_COMMIT_FILES) {
            Path p = SRC.resolve(path);
            assertTrue(Files.exists(p), "清单文件不存在：" + path);
            try {
                String text = Files.readString(p);
                assertTrue(text.contains("TenantContext"),
                        "after-commit 必须引用 TenantContext：" + path);
                assertTrue(text.contains("TenantContext.current()"),
                        "注册时必须捕获当前租户上下文：" + path);
                assertTrue(text.contains("afterCommit"),
                        "必须实现 afterCommit 回调：" + path);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    @DisplayName("AsyncConfig 装配统一 TenantTaskDecorator；测试覆盖 CallerRuns 还原")
    void asyncConfigWiresTenantDecorator() throws IOException {
        Path config = SRC.resolve("config/AsyncConfig.java");
        String text = Files.readString(config);
        assertTrue(text.contains("TenantTaskDecorator"),
                "AsyncConfig 必须装配统一 tenant task decorator");

        // CallerRuns / 跨线程合同由 WP7 契约测试覆盖，避免只写生产代码不写测试。
        Path contract = TEST_SRC.resolve("tenant/TenantWp7ContractTest.java");
        assertTrue(Files.exists(contract),
                "WP7 契约测试必须存在（bootstrap—assert—restore—clear）");
        String contractText = Files.readString(contract);
        assertTrue(contractText.contains("CallerRuns"),
                "契约测试必须覆盖 CallerRuns 同线程 scope 清理");
        assertEquals(List.of("default"),
                TenantWp7ContractProbe.singleTenants());
    }
}
