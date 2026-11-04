package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时任务 bean 必须无条件创建，否则会静默失效。
 *
 * <p>背景（踩过两次的坑）：worker 类上挂一个 {@code @ConditionalOnProperty}
 * 或 {@code @ConditionalOnBean}，条件不满足时 bean 根本不存在，调度器自然不会
 * 触发；而应用照常启动、接口照常响应，只有"定时任务没跑"这件事没有任何报错。
 * 本批在 {@code DataGovernanceRetentionCoordinator} 上抓到一个更隐蔽的版本：
 * 它带着 {@code @ConditionalOnBean}，而 {@code central-cleanup-enabled} 默认 true，
 * 各单项清理任务看到 true 就直接 return —— 协调器没被创建时，全部 TTL 清理停摆。</p>
 *
 * <p>这条测试不做运行期验证（那是 {@code verify-scheduled-jobs-e2e.py} 的事），
 * 它只回答一个问题：<strong>有没有定时任务被条件装配关掉</strong>。新增一个
 * 默认关闭的 worker 必须显式写进 {@link #EXPLICIT_OPT_IN_OPTIONAL_WORKERS}，
 * 让"默认不跑"成为一个被记录的决定，而不是又一处静默。</p>
 */
class ScheduledWorkerRegistrationTest {

    /**
     * 明确选择「默认不创建」的 worker。这些 worker 依赖外部投递配置，
     * 无条件创建也只会在每个周期里空转报错，所以默认关闭是设计决定。
     * 新增条目必须同时说明为什么默认关闭是安全的。
     */
    private static final Set<Class<?>> EXPLICIT_OPT_IN_OPTIONAL_WORKERS = Set.of(
            com.springairag.core.alertdelivery.AlertNotificationDeliveryWorker.class
    );

    @Test
    void everyScheduledWorkerIsUnconditionallyRegistered() throws Exception {
        var scheduledClasses = scanScheduledClasses();
        assertFalse(scheduledClasses.isEmpty(),
                "scanner found no @Scheduled methods; the test would pass vacuously");

        // 这几个是用户明确要求验证的：TTL/保留清理、摘要压缩依赖的清理协调器、
        // 嵌入 worker、评测 worker、长运行操作 worker。
        assertTrue(scheduledClasses.containsKey(
                        com.springairag.core.service.DataGovernanceRetentionCoordinator.class),
                "the central TTL/retention coordinator must be scheduled; when this bean "
                        + "is missing every individual retention job short-circuits silently");
        assertTrue(scheduledClasses.containsKey(
                        com.springairag.core.embeddingjob.EmbeddingJobWorker.class),
                "embedding job worker must be scheduled");
        assertTrue(scheduledClasses.containsKey(
                        com.springairag.core.evaluation.EvaluationSuiteWorker.class),
                "evaluation suite worker must be scheduled");
        assertTrue(scheduledClasses.containsKey(
                        com.springairag.core.service.CollectionPurgeOperationWorker.class),
                "collection purge worker must be scheduled");

        var problems = new TreeSet<String>();
        for (var entry : scheduledClasses.entrySet()) {
            Class<?> worker = entry.getKey();
            if (worker.isAnnotationPresent(ConditionalOnBean.class)) {
                problems.add(worker.getName() + ": @ConditionalOnBean on a @Scheduled bean. "
                        + "@ConditionalOnBean is only reliable on auto-configuration classes; "
                        + "on a scanned component it can silently skip the worker.");
            }
            ConditionalOnProperty property = worker.getAnnotation(ConditionalOnProperty.class);
            if (property != null && !property.matchIfMissing()
                    && !EXPLICIT_OPT_IN_OPTIONAL_WORKERS.contains(worker)) {
                problems.add(worker.getName() + ": @ConditionalOnProperty(matchIfMissing=false) "
                        + "without an entry in EXPLICIT_OPT_IN_OPTIONAL_WORKERS. A worker that "
                        + "is off by default but is not declared here is how scheduling dies "
                        + "without a single error.");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * 从源码目录普查所有带 {@code @Scheduled} 的文件。
     *
     * <p>不用 Spring 的 classpath scanner：它会把带 {@code @Profile} 的类按当前
     * 环境过滤掉（{@code CollectionPurgeOperationWorker} 就是 {@code @Profile("postgresql")}），
     * 而本测试要的恰恰是"源码里有哪些定时任务"，不是"当前 profile 下装了哪些"。</p>
     */
    private TreeMap<Class<?>, Set<String>> scanScheduledClasses() throws Exception {
        var found = new TreeMap<Class<?>, Set<String>>(
                (a, b) -> a.getName().compareTo(b.getName()));
        var sources = new java.io.File("src/main/java");
        assertTrue(sources.isDirectory(),
                "run this test from the spring-ai-rag-core module (src/main/java not found)");
        var javaFiles = new java.util.ArrayList<java.nio.file.Path>();
        try (var paths = java.nio.file.Files.walk(sources.toPath())) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(javaFiles::add);
        }
        for (var path : javaFiles) {
            String text = java.nio.file.Files.readString(path);
            if (!text.contains("@Scheduled")) {
                continue;
            }
            var packageMatcher = java.util.regex.Pattern
                    .compile("package\\s+([\\w.]+);").matcher(text);
            assertTrue(packageMatcher.find(), "no package declaration in " + path);
            String simpleName = path.getFileName().toString().replace(".java", "");
            Class<?> type = Class.forName(packageMatcher.group(1) + "." + simpleName,
                    false, Thread.currentThread().getContextClassLoader());
            Set<String> methods = new TreeSet<>();
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) {
                    methods.add(method.getName());
                }
            }
            if (!methods.isEmpty()) {
                found.put(type, methods);
            }
        }
        return found;
    }
}
