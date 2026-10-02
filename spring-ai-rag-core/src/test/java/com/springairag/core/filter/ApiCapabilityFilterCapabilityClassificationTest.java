package com.springairag.core.filter;

import com.springairag.core.security.ApiCapabilitySupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ApiCapabilityFilter#requiredCapability} 的路径分类测试。
 *
 * <p>方向与 {@link SecurityPathExclusionsTest} 相反，值得写清楚：
 * 本过滤器里返回 {@code null} 表示"这个端点不要求能力"——也就是
 * <b>更宽松</b>。因此 fail closed 在这里的含义是
 * <b>形态可疑的 URI 绝不能返回 null</b>，而"不算排除"那种直觉写法在这里是错的。
 */
@DisplayName("ApiCapabilityFilter — 能力分类")
class ApiCapabilityFilterCapabilityClassificationTest {

    private static final String READ = ApiCapabilitySupport.RAG_READ;
    private static final String WRITE = ApiCapabilitySupport.RAG_WRITE;

    @Nested
    @DisplayName("既有分类保持不变")
    class Unchanged {

        @Test
        void readVerbsNeedRead() {
            assertEquals(READ, ApiCapabilityFilter.requiredCapability("GET", "/api/v1/rag/documents"));
            assertEquals(READ, ApiCapabilityFilter.requiredCapability("HEAD", "/api/v1/rag/documents"));
            assertEquals(READ, ApiCapabilityFilter.requiredCapability("OPTIONS", "/api/v1/rag/documents"));
        }

        @Test
        void writeVerbsNeedWrite() {
            assertEquals(WRITE, ApiCapabilityFilter.requiredCapability("POST", "/api/v1/rag/documents"));
            assertEquals(WRITE, ApiCapabilityFilter.requiredCapability("PUT", "/api/v1/rag/documents/1"));
            assertEquals(WRITE, ApiCapabilityFilter.requiredCapability("PATCH", "/api/v1/rag/documents/1"));
            assertEquals(WRITE, ApiCapabilityFilter.requiredCapability("DELETE", "/api/v1/rag/documents/1"));
        }

        @Test
        void readPostPathsAreDowngraded() {
            assertEquals(READ, ApiCapabilityFilter.requiredCapability("POST", "/api/v1/rag/search"));
            assertEquals(READ, ApiCapabilityFilter.requiredCapability("POST", "/api/v1/rag/chat/ask"));
            assertEquals(READ, ApiCapabilityFilter.requiredCapability("POST", "/v1/chat/completions"));
        }

        @Test
        void managementAndIdentityPathsNeedNoCapability() {
            assertNull(ApiCapabilityFilter.requiredCapability("POST", "/api/v1/rag/api-keys"));
            assertNull(ApiCapabilityFilter.requiredCapability("POST", "/api/v1/rag/api-keys/abc"));
            assertNull(ApiCapabilityFilter.requiredCapability("POST", "/api/v1/rag/alerts"));
            assertNull(ApiCapabilityFilter.requiredCapability("GET", "/api/v1/rag/auth"));
            assertNull(ApiCapabilityFilter.requiredCapability("GET", "/api/v1/rag/integration-capabilities"));
        }

        @Test
        void nonDataPlanePathsNeedNoCapability() {
            assertNull(ApiCapabilityFilter.requiredCapability("GET", "/actuator/health"));
            assertNull(ApiCapabilityFilter.requiredCapability("GET", "/swagger-ui.html"));
        }

        @Test
        void trailingSlashAndQueryAreNormalised() {
            assertEquals(READ, ApiCapabilityFilter.requiredCapability("GET", "/api/v1/rag/documents/"));
            assertEquals(READ, ApiCapabilityFilter.requiredCapability("GET", "/api/v1/rag/documents?page=1"));
            assertNull(ApiCapabilityFilter.requiredCapability("POST", "/api/v1/rag/api-keys/"));
        }
    }

    @Nested
    @DisplayName("分段感知：身份路径的前缀不再顺带匹配")
    class SegmentAware {

        @Test
        void apiKeysLookalikeIsNotAnIdentityPath() {
            // 旧写法 startsWith("/api/v1/rag/api-keys/") 已经分段安全，
            // 但 "/api/v1/rag/api-keys" 这一支若哪天去掉尾部斜杠就回退到朴素前缀。
            // 这条用例钉住"看起来像身份路径、其实不是"的形态。
            assertEquals(WRITE, ApiCapabilityFilter.requiredCapability(
                    "POST", "/api/v1/rag/api-keys-foo"));
            assertEquals(WRITE, ApiCapabilityFilter.requiredCapability(
                    "POST", "/api/v1/rag/alerts-archive"));
        }
    }

    @Nested
    @DisplayName("形态可疑的 URI 拿不到任何豁免（fail closed）")
    class FailClosed {

        @Test
        @DisplayName("穿越形态不被当成身份/管理路径")
        void traversalIsNotAIdentityPath() {
            // 修复前：这条会命中 startsWith("/api/v1/rag/api-keys/")，
            // 于是返回 null —— 一个没有任何 RAG 能力的 principal
            // 会被放行去做数据面写操作。
            assertEquals(WRITE, ApiCapabilityFilter.requiredCapability(
                    "POST", "/api/v1/rag/api-keys/../chat"));
            assertEquals(READ, ApiCapabilityFilter.requiredCapability(
                    "GET", "/api/v1/rag/alerts/../documents"));
        }

        @Test
        void traversalIsNotDowngradedToRead() {
            // 也不该命中只读 POST 列表。
            assertEquals(WRITE, ApiCapabilityFilter.requiredCapability(
                    "POST", "/api/v1/rag/chat/ask/../../documents"));
        }

        @Test
        void encodedSeparatorsAreNotExempt() {
            assertEquals(WRITE, ApiCapabilityFilter.requiredCapability(
                    "POST", "/api/v1/rag/api-keys%2F..%2Fchat"));
            assertEquals(READ, ApiCapabilityFilter.requiredCapability(
                    "GET", "/api/v1/rag/documents%2e%2e%2fapi-keys"));
        }

        @Test
        void unknownVerbStillDemandsWriteRatherThanNoCheck() {
            // 未知动词在正常路径上返回 null（不要求能力），
            // 但形态可疑时不能沿用这个宽松结论。
            assertEquals(WRITE, ApiCapabilityFilter.requiredCapability(
                    "TRACE", "/api/v1/rag/api-keys/../chat"));
        }
    }
}
