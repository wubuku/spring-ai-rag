package com.springairag.core.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecurityPathExclusions} 的行为测试。
 *
 * <p>这条判定同时决定"跳过鉴权"与"跳过限流"，两个方向都是安全敏感的：
 * 判得太宽会静默放过受保护端点，判得太窄会让本该公开的健康检查突然 401。
 */
@DisplayName("SecurityPathExclusions — 排除判定")
class SecurityPathExclusionsTest {

    @Nested
    @DisplayName("真实存在的公开端点仍然被排除")
    class RealEndpoints {

        @Test
        void actuatorFamily() {
            assertTrue(SecurityPathExclusions.isExcluded("/actuator", List.of()));
            assertTrue(SecurityPathExclusions.isExcluded("/actuator/health", List.of()));
            // k8s 探针实际用的就是这两条
            assertTrue(SecurityPathExclusions.isExcluded("/actuator/health/liveness", List.of()));
            assertTrue(SecurityPathExclusions.isExcluded("/actuator/health/readiness", List.of()));
        }

        @Test
        void swaggerFamily() {
            // springdoc.swagger-ui.path 的默认值
            assertTrue(SecurityPathExclusions.isExcluded("/swagger-ui.html", List.of()));
            assertTrue(SecurityPathExclusions.isExcluded("/swagger-ui", List.of()));
            assertTrue(SecurityPathExclusions.isExcluded("/swagger-ui/index.html", List.of()));
        }

        @Test
        void apiDocsFamily() {
            assertTrue(SecurityPathExclusions.isExcluded("/v3/api-docs", List.of()));
            assertTrue(SecurityPathExclusions.isExcluded("/v3/api-docs/swagger-config", List.of()));
        }

        @Test
        void healthFamily() {
            // RagHealthController 映射了 /health 与 /health/components
            assertTrue(SecurityPathExclusions.isExcluded("/health", List.of()));
            assertTrue(SecurityPathExclusions.isExcluded("/health/components", List.of()));
        }

        @Test
        void errorPath() {
            assertTrue(SecurityPathExclusions.isExcluded("/error", List.of()));
        }

        @Test
        void callerSuppliedExactPaths() {
            // legacy auth 模式下公开的 cache/stats
            assertTrue(SecurityPathExclusions.isExcluded(
                    "/api/v1/rag/cache/stats", List.of("/api/v1/rag/cache/stats")));
            assertFalse(SecurityPathExclusions.isExcluded("/api/v1/rag/cache/stats", List.of()));
        }
    }

    @Nested
    @DisplayName("分段感知：前缀不再顺带匹配同类词")
    class SegmentAware {

        @Test
        void healthzIsNotAHealthEndpoint() {
            // 旧写法 startsWith("/health") 会把 /healthz 一起排除，
            // 将来若新增该端点，它会**静默地不受鉴权**。
            assertFalse(SecurityPathExclusions.isExcluded("/healthz", List.of()));
            assertFalse(SecurityPathExclusions.isExcluded("/healthcheck", List.of()));
            assertFalse(SecurityPathExclusions.isExcluded("/healthcheck/ready", List.of()));
        }

        @Test
        void actuatorLookalikesAreNotExcluded() {
            assertFalse(SecurityPathExclusions.isExcluded("/actuatorfoo", List.of()));
            assertFalse(SecurityPathExclusions.isExcluded("/actuator-admin", List.of()));
        }

        @Test
        void swaggerUiHtmlIsExactOnly() {
            // /swagger-ui.html 需要整条匹配；/swagger-uix 不是公开端点
            assertFalse(SecurityPathExclusions.isExcluded("/swagger-uix.html", List.of()));
            assertFalse(SecurityPathExclusions.isExcluded("/swagger-uix", List.of()));
        }

        @Test
        void errorLookalikes() {
            assertFalse(SecurityPathExclusions.isExcluded("/errors", List.of()));
            assertFalse(SecurityPathExclusions.isExcluded("/errorreport", List.of()));
        }

        @Test
        void protectedApiPathsAreNeverExcluded() {
            assertFalse(SecurityPathExclusions.isExcluded("/api/v1/rag/documents", List.of()));
            assertFalse(SecurityPathExclusions.isExcluded("/api/v1/rag/chat", List.of()));
            assertFalse(SecurityPathExclusions.isExcluded("/v1/chat/completions", List.of()));
        }
    }

    @Nested
    @DisplayName("形态可疑的 URI 一律不算排除（fail closed）")
    class Ambiguous {

        @Test
        void traversalSegmentsAreNotExcluded() {
            // 原始路径以 /actuator 开头，规范化后却指向受保护端点。
            // 能否真的走通取决于容器的规范化策略，本项目无法起真实容器实测，
            // 所以这里不去断言"可利用"，而是让判定不依赖那个前提。
            assertTrue(SecurityPathExclusions.isAmbiguous("/actuator/../api/v1/rag/documents"));
            assertFalse(SecurityPathExclusions.isExcluded(
                    "/actuator/../api/v1/rag/documents", List.of()));
        }

        @Test
        void singleDotSegmentsAreNotExcluded() {
            assertTrue(SecurityPathExclusions.isAmbiguous("/swagger-ui/./../../api/v1/rag/documents"));
            assertFalse(SecurityPathExclusions.isExcluded(
                    "/swagger-ui/./../../api/v1/rag/documents", List.of()));
        }

        @Test
        void encodedTraversalAndSeparatorsAreNotExcluded() {
            assertFalse(SecurityPathExclusions.isExcluded("/actuator%2f..%2fapi%2fv1%2frag%2fdocuments", List.of()));
            assertFalse(SecurityPathExclusions.isExcluded("/swagger-ui%2e%2e/x", List.of()));
            assertFalse(SecurityPathExclusions.isExcluded("/health%00/../api", List.of()));
            assertFalse(SecurityPathExclusions.isExcluded("/swagger-ui\\..\\api", List.of()));
        }

        @Test
        void percentEncodingIsMatchedCaseInsensitively() {
            assertTrue(SecurityPathExclusions.isAmbiguous("/actuator/%2E%2E/api"));
            assertTrue(SecurityPathExclusions.isAmbiguous("/actuator/%2F/api"));
            assertTrue(SecurityPathExclusions.isAmbiguous("/actuator/%5C/api"));
        }

        @Test
        void ordinaryPathsAreNotAmbiguous() {
            assertFalse(SecurityPathExclusions.isAmbiguous("/actuator/health/liveness"));
            assertFalse(SecurityPathExclusions.isAmbiguous("/api/v1/rag/documents"));
            assertFalse(SecurityPathExclusions.isAmbiguous("/health/components"));
            // 路径里出现点或双点但不是独立分段
            assertFalse(SecurityPathExclusions.isAmbiguous("/api/v1/rag/documents.md"));
            assertFalse(SecurityPathExclusions.isAmbiguous("/api/v1/rag/report..json"));
        }
    }

    @Nested
    @DisplayName("边界输入")
    class Boundaries {

        @Test
        void nullAndEmptyAreNotExcluded() {
            assertFalse(SecurityPathExclusions.isExcluded(null, List.of()));
            assertFalse(SecurityPathExclusions.isExcluded("", List.of()));
        }

        @Test
        void nullExactPathCollectionIsTolerated() {
            assertTrue(SecurityPathExclusions.isExcluded("/health", null));
        }

        @Test
        void rootIsNotExcluded() {
            assertFalse(SecurityPathExclusions.isExcluded("/", List.of()));
        }

        @Test
        void trailingSlashStillMatches() {
            assertTrue(SecurityPathExclusions.isExcluded("/actuator/", List.of()));
            assertTrue(SecurityPathExclusions.isExcluded("/health/", List.of()));
        }
    }

    @Nested
    @DisplayName("两个过滤器对同一个 URI 必须给出同一个答案")
    class CrossFilter {

        @Test
        void authAndRateLimitAgree() {
            String[] probes = {
                "/actuator", "/actuator/health/liveness", "/swagger-ui.html",
                "/swagger-ui/index.html", "/v3/api-docs", "/health",
                "/health/components", "/error", "/healthz", "/actuatorfoo",
                "/api/v1/rag/documents", "/actuator/../api/v1/rag/documents",
            };
            for (String path : probes) {
                boolean rateLimit = new RateLimitFilter(false, 0).isExcludedPath(path);
                // ApiKeyAuthFilter 的判定走同一条共享逻辑，这里用同一条入口复核，
                // 避免两处清单日后各自漂移。
                boolean auth = SecurityPathExclusions.isExcluded(path, List.of());
                if (rateLimit != auth) {
                    throw new AssertionError(
                            "filters disagree on " + path + ": rateLimit=" + rateLimit + " auth=" + auth);
                }
            }
        }
    }
}
