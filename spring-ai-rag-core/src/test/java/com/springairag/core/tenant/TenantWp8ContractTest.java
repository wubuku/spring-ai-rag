package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP8：结果 owner / 回调权威 / SSE 逐次授权的统一合同测试。
 *
 * <p>反模式红测先证明「volatile registry + 任意 task ID」「body-trusting callback」
 * 「建连时一次授权」会越权；推荐实现绿测证明合同可闭合。不调用真实外部 Provider。</p>
 *
 * <p>参考 ../seedance-research/multitenancy-verification 案例 05/06/07 的
 * bad/good 实验矩阵；本仓只承接与本仓对象模型（tenant/collection/owner/receipt）
 * 对应的通用合同，不依赖外部项目私有协议。</p>
 */
@DisplayName("WP8 结果 owner / 回调权威 / SSE 逐次授权")
class TenantWp8ContractTest {

    // ------------------------------------------------------------------
    // 1) 结果 owner：任意 task ID 不得触发外部查询
    // ------------------------------------------------------------------

    @Test
    @DisplayName("反模式：volatile registry 命中不验 owner，miss 会把任意 task ID 变成外部查询")
    void volatileResultRegistryIsUnsafeAntiPattern() {
        var unsafe = new VolatileResultRegistry();
        unsafe.put("job-a", "https://media.example/a.mp3");

        // 命中缓存：Bob（非 owner）也拿到结果 URL。
        assertEquals("https://media.example/a.mp3",
                unsafe.readIfPresent("job-a").orElseThrow(),
                "反模式应暴露：cache hit 不检查 owner");
        // miss：任意 ID 触发外部查询（本实验用计数器模拟 Provider，不联网）。
        AtomicInteger providerQueries = new AtomicInteger();
        unsafe.readOrQuery("arbitrary-task-id", () -> {
            providerQueries.incrementAndGet();
            return Optional.<String>empty();
        });
        assertEquals(1, providerQueries.get(),
                "反模式应暴露：任意 task ID 触发外部 Provider 查询");
    }

    @Test
    @DisplayName("推荐：owner/tenant/receipt 持久化结果 reader 对无权/跨租户/未知 ID 统一拒绝且不查 Provider")
    void durableOwnerReaderRejectsWithoutProviderQuery() {
        AtomicInteger providerQueries = new AtomicInteger();
        Supplier<Optional<String>> forbiddenProviderQuery = () -> {
            providerQueries.incrementAndGet();
            throw new AssertionError("任意 task ID 不得触发外部 Provider 查询");
        };
        var reader = new TenantResultOwnerReader(List.of(
                new TenantResultReceipt("job-a", "tenant-a", "collection-a",
                        "actor-alice", "receipt-a", "https://media.example/a.mp3")))
                .withProviderQuery(forbiddenProviderQuery);

        assertEquals("https://media.example/a.mp3",
                reader.read("tenant-a", "actor-alice", "job-a").orElseThrow());
        assertTrue(reader.read("tenant-a", "actor-bob", "job-a").isEmpty(),
                "同租户非 owner 必须拒绝");
        assertTrue(reader.read("tenant-b", "actor-alice", "job-a").isEmpty(),
                "跨租户必须拒绝");
        assertTrue(reader.read("tenant-a", "actor-alice", "arbitrary-task-id").isEmpty(),
                "未知 ID 必须统一 not-found");
        assertEquals(0, providerQueries.get(),
                "无权、跨租户、未知 ID 均不得触发外部 Provider 查询");
    }

    @Test
    @DisplayName("结果 receipt 必须携带 tenant/owner/collection，缺一不可")
    void resultReceiptRequiresOwnerTenantCollection() {
        assertThrows(IllegalArgumentException.class,
                () -> new TenantResultReceipt("job-a", null, "collection-a",
                        "actor-alice", "receipt-a", "url"));
        assertThrows(IllegalArgumentException.class,
                () -> new TenantResultReceipt("job-a", "tenant-a", "collection-a",
                        null, "receipt-a", "url"));
        assertThrows(IllegalArgumentException.class,
                () -> new TenantResultReceipt("job-a", "tenant-a", null,
                        "actor-alice", "receipt-a", "url"));
    }

    // ------------------------------------------------------------------
    // 2) 回调权威：receipt / 签名 / replay / response-lost 不二次 create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("反模式：body-trusting callback 把 body 状态和 URL 当权威")
    void bodyTrustingCallbackIsUnsafeAntiPattern() {
        var unsafe = new BodyTrustingCallbackProcessor();
        var decision = unsafe.process(new TenantCallbackRequest(
                "signed-operation", "provider-a", "provider-task-1",
                Instant.parse("2026-11-14T00:00:00Z"), "nonce-1", "bad-signature",
                "SUCCEEDED", "https://attacker.example/fake.mp3"));
        assertEquals("SUCCEEDED", decision.status(),
                "反模式应暴露：未验签也采信 body status");
        assertEquals("https://attacker.example/fake.mp3", decision.resultUrl(),
                "反模式应暴露：未验签也采信 body resultUrl");
    }

    @Test
    @DisplayName("推荐：签验→receipt→replay→权威 Provider 结果；错签/错配/未知 task 统一 UNKNOWN")
    void callbackAuthorityRejectsUntrustedPaths() {
        Instant now = Instant.parse("2026-11-14T00:00:10Z");
        var receipt = new TenantCallbackReceipt("signed-operation", "provider-a",
                "provider-task-1", "tenant-a", "job-a", "actor-alice");
        AtomicInteger authoritativeQueries = new AtomicInteger();
        var processor = new TenantCallbackProcessor(
                List.of(new TenantCallbackContract("signed-operation", true, "secret-a")),
                List.of(receipt),
                r -> {
                    authoritativeQueries.incrementAndGet();
                    return new TenantProviderResult(r.operation(), r.providerName(),
                            r.providerTaskId(), "SUCCEEDED", "https://media.example/a.mp3");
                },
                java.time.Duration.ofMinutes(5));

        String validSignature = TenantCallbackSignature.sign("secret-a",
                "signed-operation", "provider-a", "provider-task-1",
                Instant.parse("2026-11-14T00:00:00Z"), "nonce-1",
                "FAILED", "https://attacker.example/fake.mp3");
        var applied = processor.process(new TenantCallbackRequest(
                "signed-operation", "provider-a", "provider-task-1",
                Instant.parse("2026-11-14T00:00:00Z"), "nonce-1", validSignature,
                "FAILED", "https://attacker.example/fake.mp3"), now);

        assertEquals(TenantCallbackDecision.Outcome.APPLIED, applied.outcome());
        assertEquals("SUCCEEDED", applied.status(), "必须采信权威 Provider 而非 body");
        assertEquals("https://media.example/a.mp3", applied.resultUrl());
        assertEquals(1, authoritativeQueries.get());

        // 错签：已知与未知 task 决策一致，不形成 task-existence oracle，且不查权威 Provider。
        int before = authoritativeQueries.get();
        var badSignature = processor.process(new TenantCallbackRequest(
                "signed-operation", "provider-a", "provider-task-1",
                Instant.parse("2026-11-14T00:00:00Z"), "nonce-2", "invalid",
                "SUCCEEDED", "https://attacker.example/fake.mp3"), now);
        var unknownTask = processor.process(new TenantCallbackRequest(
                "signed-operation", "provider-a", "unknown-task",
                Instant.parse("2026-11-14T00:00:00Z"), "nonce-3", "invalid",
                "SUCCEEDED", "https://attacker.example/fake.mp3"), now);
        assertEquals(TenantCallbackDecision.Outcome.REJECTED, badSignature.outcome());
        assertEquals(TenantCallbackDecision.Outcome.REJECTED, unknownTask.outcome());
        assertEquals(before, authoritativeQueries.get(), "验签失败不得查权威 Provider");

        // 有效 nonce 重放不得二次应用。
        var replay = processor.process(new TenantCallbackRequest(
                "signed-operation", "provider-a", "provider-task-1",
                Instant.parse("2026-11-14T00:00:00Z"), "nonce-1", validSignature,
                "FAILED", "https://attacker.example/fake.mp3"), now);
        assertEquals(TenantCallbackDecision.Outcome.REJECTED, replay.outcome());

        // receipt operation/provider/task 错配 → UNKNOWN，不采信 body。
        var mismatched = processor.process(new TenantCallbackRequest(
                "signed-operation", "provider-a", "other-task",
                Instant.parse("2026-11-14T00:00:00Z"), "nonce-4",
                TenantCallbackSignature.sign("secret-a", "signed-operation",
                        "provider-a", "other-task",
                        Instant.parse("2026-11-14T00:00:00Z"), "nonce-4",
                        "SUCCEEDED", "https://attacker.example/fake.mp3"),
                "SUCCEEDED", "https://attacker.example/fake.mp3"), now);
        assertEquals(TenantCallbackDecision.Outcome.UNKNOWN, mismatched.outcome());
    }

    @Test
    @DisplayName("unsigned operation 只唤醒，不采信 body 状态/URL；manual audit principal 与 callback actor 分离")
    void unsignedCallbackWakeUpOnlyKeepsManualAuditPrincipal() {
        Instant now = Instant.parse("2026-11-14T00:00:10Z");
        var receipt = new TenantCallbackReceipt("unsigned-operation", "provider-b",
                "provider-task-2", "tenant-b", "job-b", "actor-bob");
        var processor = new TenantCallbackProcessor(
                List.of(new TenantCallbackContract("unsigned-operation", false, "")),
                List.of(receipt),
                r -> { throw new AssertionError("unsigned 不得查权威 Provider 结果"); },
                java.time.Duration.ofMinutes(5));
        var decision = processor.process(new TenantCallbackRequest(
                "unsigned-operation", "provider-b", "provider-task-2",
                Instant.parse("2026-11-14T00:00:00Z"), "nonce-u1", null,
                "SUCCEEDED", "https://attacker.example/fake.mp3"), now);
        assertEquals(TenantCallbackDecision.Outcome.WAKE_UP_ONLY, decision.outcome());
        assertTrue(decision.status() == null || "UNKNOWN".equals(decision.status()));
        assertTrue(decision.resultUrl() == null);
        assertEquals("job-b", decision.wakeUpReceipt().ownerReference());
        assertEquals("actor-bob", decision.wakeUpReceipt().actorId());
        assertEquals("callback:provider-b", decision.auditPrincipal());
        assertEquals("manual-review:job-b", decision.manualAuditPrincipal());
    }

    @Test
    @DisplayName("response-lost 重试：durable PENDING intent 先查已有 attempt，不二次 create")
    void responseLostRetryDoesNotCreateSecondProviderTask() {
        var store = new TenantDurableCallbackEvidenceStore();
        var coordinator = new TenantProviderReferenceCoordinator(store);
        AtomicInteger createCalls = new AtomicInteger();
        Supplier<String> create = () -> {
            createCalls.incrementAndGet();
            return "provider-task-once";
        };
        Supplier<Optional<String>> query = () -> Optional.of("provider-task-once");

        var first = coordinator.recoverOrCreate("operation-a", "provider-a",
                "client-token-1", "tenant-a", "actor-alice", "job-a", query, create);
        // 模拟响应丢失：新建 coordinator（进程重启）后同一 clientToken 再跑。
        var restarted = new TenantProviderReferenceCoordinator(store);
        var recovered = restarted.recoverOrCreate("operation-a", "provider-a",
                "client-token-1", "tenant-a", "actor-alice", "job-a", query, create);

        assertEquals("provider-task-once", first.providerTaskId());
        assertEquals("provider-task-once", recovered.providerTaskId());
        assertEquals(1, createCalls.get(), "response-lost 重试不得二次 create");
    }

    // ------------------------------------------------------------------
    // 3) SSE 每次投递重查 ACL + 撤权停止 + 多副本
    // ------------------------------------------------------------------

    @Test
    @DisplayName("反模式：建连时一次授权，撤权后旧订阅仍收到事件")
    void unconditionalSsePublisherIsUnsafeAntiPattern() {
        var unsafe = new UnconditionalSsePublisher();
        var subscription = new TenantSseSubscription("tenant-a", "actor-alice", "collection-a");
        var event = new TenantSseEvent("tenant-a", "collection-a", "progress", "chunk");
        unsafe.open(subscription, true);
        // 撤权后反模式仍按旧订阅推送。
        assertTrue(unsafe.publishIfOpen(subscription, event).isPresent(),
                "反模式应暴露：ACL 撤销后仍发送");
    }

    @Test
    @DisplayName("推荐：撤权后立即停止推送；跨租户/跨 collection 事件拒绝；两个副本各自重查")
    void authorizedSsePublisherRechecksEachDelivery() {
        AtomicInteger aclChecks = new AtomicInteger();
        TenantSseAccessChecker checker = (tenant, actor, collection) -> {
            aclChecks.incrementAndGet();
            // 外部授权源：撤销后返回 false。
            return !"revoked".equals(System.getProperty("wp8.test.acl.state"));
        };
        TenantContext.set(new TenantContext(
                "tenant-a", "actor-alice", TenantMode.STRICT));
        try {
            var publisher = new TenantAuthorizedSsePublisher(checker);
            var subscription = new TenantSseSubscription(
                    "tenant-a", "actor-alice", "collection-a");
            var ownEvent = new TenantSseEvent(
                    "tenant-a", "collection-a", "progress", "chunk-1");
            var crossTenant = new TenantSseEvent(
                    "tenant-b", "collection-a", "progress", "chunk-2");
            var crossCollection = new TenantSseEvent(
                    "tenant-a", "collection-b", "progress", "chunk-3");

            assertTrue(publisher.publishIfAuthorized(subscription, ownEvent).isPresent());
            assertTrue(publisher.publishIfAuthorized(subscription, crossTenant).isEmpty());
            assertTrue(publisher.publishIfAuthorized(subscription, crossCollection).isEmpty());

            // 撤权：两个模拟副本共享授权源，各自在投递点重查。
            System.setProperty("wp8.test.acl.state", "revoked");
            try {
                var replicaA = new TenantAuthorizedSsePublisher(checker);
                var replicaB = new TenantAuthorizedSsePublisher(checker);
                assertTrue(replicaA.publishIfAuthorized(subscription, ownEvent).isEmpty(),
                        "副本 A 撤权后必须停止推送");
                assertTrue(replicaB.publishIfAuthorized(subscription, ownEvent).isEmpty(),
                        "副本 B 撤权后必须停止推送");
            } finally {
                System.clearProperty("wp8.test.acl.state");
            }
            // 归属不匹配的事件在 ACL 查询前快速拒绝；对允许路径的每次投递
            // （正向 1 次 + 两个副本各 1 次）都必须重查当前 ACL。
            assertTrue(aclChecks.get() >= 3,
                    "允许路径的每次投递都必须重查 ACL，不能复用建连时的旧结果");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("SSE 订阅/事件必须带 tenant 与 collection，无 TenantContext 时 fail-closed")
    void sseSubscriptionRequiresTenantScopeAndFailsClosedWithoutContext() {
        assertThrows(IllegalArgumentException.class,
                () -> new TenantSseSubscription(null, "actor-alice", "collection-a"));
        assertThrows(IllegalArgumentException.class,
                () -> new TenantSseEvent(null, "collection-a", "progress", "chunk"));
        TenantContext.clear();
        var publisher = new TenantAuthorizedSsePublisher(
                (tenant, actor, collection) -> true);
        var subscription = new TenantSseSubscription("tenant-a", "actor-alice", "collection-a");
        var event = new TenantSseEvent("tenant-a", "collection-a", "progress", "chunk");
        assertThrows(IllegalStateException.class,
                () -> publisher.publishIfAuthorized(subscription, event),
                "无租户上下文必须 fail-closed，不得推送");
    }

    // ------------------------------------------------------------------
    // 测试 source set 内的反模式 fixture（不进入 src/main）
    // ------------------------------------------------------------------

    /** 参考项目案例 06 bad/VolatileResultRegistry 的本仓等价反模式。 */
    private static final class VolatileResultRegistry {
        private final java.util.Map<String, String> cache = new java.util.HashMap<>();

        void put(String taskId, String resultUrl) {
            cache.put(taskId, resultUrl);
        }

        Optional<String> readIfPresent(String taskId) {
            return Optional.ofNullable(cache.get(taskId));
        }

        Optional<String> readOrQuery(String taskId, Supplier<Optional<String>> providerQuery) {
            return Optional.ofNullable(cache.get(taskId)).or(providerQuery);
        }
    }

    /** 参考项目案例 05 bad/BodyTrustingCallbackProcessor 的本仓等价反模式。 */
    private record BodyTrustingDecision(String status, String resultUrl) {
    }

    private static final class BodyTrustingCallbackProcessor {
        BodyTrustingDecision process(TenantCallbackRequest request) {
            return new BodyTrustingDecision(request.bodyStatus(), request.bodyResultUrl());
        }
    }

    /** 参考项目案例 07 bad/UnconditionalSsePublisher 的本仓等价反模式。 */
    private static final class UnconditionalSsePublisher {
        private final java.util.Set<TenantSseSubscription> open = new java.util.HashSet<>();

        void open(TenantSseSubscription subscription, boolean initiallyAuthorized) {
            if (initiallyAuthorized) {
                open.add(subscription);
            }
        }

        Optional<TenantSseEvent> publishIfOpen(
                TenantSseSubscription subscription, TenantSseEvent event) {
            return open.contains(subscription) ? Optional.of(event) : Optional.empty();
        }
    }
}
