package com.springairag.core.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 结果分类的<strong>全量边界</strong>矩阵（Batch 766）。
 *
 * <p>这个枚举同时驱动两处：Micrometer 的 {@code rag.integration.requests}
 * 指标标签，以及 {@code IntegrationObservabilityQueryService} 的聚合维度。
 * 也就是说，分类错了会直接让 SLO 算错、告警静默——而这正是最需要测试网的地方：
 * <strong>没有任何一个测试直接调用过 {@code from(int)}</strong>，
 * 十个分支全靠 {@code IntegrationObservationFilter} 间接带过。
 *
 * <p>本类把 0–599 之外的所有边界都钉死，重点是三处容易在重构中被改坏的语义：
 * <ul>
 *   <li>1xx / 3xx / 6xx 都必须落 {@code OTHER}——它们既不是成功也不是错误。</li>
 *   <li>4xx 只有白名单里的八个算 {@code CLIENT_ERROR}；402/406/410/418/451
 *       都是 4xx 却不在白名单，必须落 {@code OTHER}。<strong>这一点是刻意的</strong>：
 *       分类是低基数枚举，把全部 4xx 收进来会让"客户端错误率"这个指标
 *       混入 410 Gone 这类正常的资源删除响应。</li>
 *   <li>401/403/409/429 各自独立成一类，便于分别告警。</li>
 * </ul>
 */
class IntegrationHttpStatusClassBoundaryTest {

    private static IntegrationHttpStatusClass classify(int status) {
        return IntegrationHttpStatusClass.from(status);
    }

    // ==================== 2xx ====================

    @ParameterizedTest(name = "2xx → SUCCESS：{0}")
    @ValueSource(ints = {200, 201, 202, 204, 206, 299})
    @DisplayName("2xx 区间含两端全部为 SUCCESS")
    void successRangeIsInclusive(int status) {
        assertEquals(IntegrationHttpStatusClass.SUCCESS, classify(status));
    }

    // ==================== 5xx ====================

    @ParameterizedTest(name = "5xx → SERVER_ERROR：{0}")
    @ValueSource(ints = {500, 502, 503, 504, 599})
    @DisplayName("5xx 区间含两端全部为 SERVER_ERROR")
    void serverErrorRangeIsInclusive(int status) {
        assertEquals(IntegrationHttpStatusClass.SERVER_ERROR, classify(status));
    }

    // ==================== 独立成类的四个状态码 ====================

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "401, UNAUTHENTICATED",
            "403, FORBIDDEN",
            "409, CONFLICT",
            "429, RATE_LIMITED",
    })
    @DisplayName("401/403/409/429 各自独立分类")
    void specialClientStatusesAreClassifiedIndividually(
            int status, IntegrationHttpStatusClass expected) {
        assertEquals(expected, classify(status));
    }

    @Test
    @DisplayName("四个特殊状态码彼此不会串类")
    void specialClientStatusesDoNotCollide() {
        Set<IntegrationHttpStatusClass> seen = EnumSet.noneOf(
                IntegrationHttpStatusClass.class);
        for (int status : new int[] {401, 403, 409, 429}) {
            assertTrue(seen.add(classify(status)),
                    () -> status + " 与前面的状态码落到了同一类");
        }
        assertEquals(4, seen.size());
    }

    // ==================== 4xx 白名单 ====================

    @ParameterizedTest(name = "白名单 4xx → CLIENT_ERROR：{0}")
    @ValueSource(ints = {400, 404, 405, 408, 413, 415, 422, 425})
    @DisplayName("白名单内的 4xx 为 CLIENT_ERROR")
    void whitelistedClientErrors(int status) {
        assertEquals(IntegrationHttpStatusClass.CLIENT_ERROR, classify(status));
    }

    @ParameterizedTest(name = "非白名单 4xx → OTHER：{0}")
    @ValueSource(ints = {402, 406, 407, 410, 411, 412, 414, 416, 417, 418,
            421, 423, 424, 426, 428, 430, 431, 451})
    @DisplayName("非白名单的 4xx 落 OTHER 而不是 CLIENT_ERROR")
    void nonWhitelistedClientErrorsFallToOther(int status) {
        // 这是刻意设计而非疏漏：4xx 全收会让"客户端错误率"混入 410 Gone
        // 这类正常的资源删除响应。测试要钉住这个区别，否则有人"顺手修好"它
        // 反而会污染指标。
        assertEquals(IntegrationHttpStatusClass.OTHER, classify(status),
                () -> status + " 不在白名单内，应落 OTHER");
    }

    @Test
    @DisplayName("白名单恰好是八个状态码，多一个都会改变指标语义")
    void whitelistHasExactlyEightEntries() {
        Set<Integer> whitelist = new HashSet<>();
        for (int status = 400; status <= 430; status++) {
            if (classify(status) == IntegrationHttpStatusClass.CLIENT_ERROR) {
                whitelist.add(status);
            }
        }
        assertEquals(
                Set.of(400, 404, 405, 408, 413, 415, 422, 425), whitelist,
                "CLIENT_ERROR 白名单被改动过；这会直接改变错误率指标的构成，"
                        + "确认是有意为之并同步更新本测试");
    }

    // ==================== 1xx / 3xx / 6xx 及非法值 ====================

    @ParameterizedTest(name = "非 2xx/4xx/5xx → OTHER：{0}")
    @ValueSource(ints = {100, 101, 199, 300, 301, 302, 304, 307, 308, 399,
            600, 700, 999, 1000, Integer.MAX_VALUE})
    @DisplayName("1xx/3xx/6xx 及超大值落 OTHER")
    void nonErrorRangesFallToOther(int status) {
        assertEquals(IntegrationHttpStatusClass.OTHER, classify(status),
                () -> status + " 既不是成功也不是受管错误，应落 OTHER");
    }

    @ParameterizedTest(name = "非法状态码 → OTHER：{0}")
    @ValueSource(ints = {0, -1, -200, -999, Integer.MIN_VALUE})
    @DisplayName("零与负数状态码落 OTHER 而不是抛异常")
    void illegalStatusCodesFallToOther(int status) {
        // 探针或代理可能回传非标准状态码；分类器不能因此让整个请求观测失败。
        assertEquals(IntegrationHttpStatusClass.OTHER, classify(status));
    }

    // ==================== 区间边界紧邻 ====================

    @Test
    @DisplayName("2xx 区间下界之下是 1xx，上界之上是 3xx")
    void successRangeIsExactlyTwoHundredToTwoNinetyNine() {
        assertEquals(IntegrationHttpStatusClass.OTHER, classify(199));
        assertEquals(IntegrationHttpStatusClass.SUCCESS, classify(200));
        assertEquals(IntegrationHttpStatusClass.SUCCESS, classify(299));
        assertEquals(IntegrationHttpStatusClass.OTHER, classify(300));
    }

    @Test
    @DisplayName("5xx 区间下界之上是 4xx，上界之上是 6xx")
    void serverErrorRangeIsExactlyFiveHundredToFiveNinetyNine() {
        // 496 不在 4xx 白名单里，因此是 OTHER 而不是 CLIENT_ERROR。
        assertEquals(IntegrationHttpStatusClass.OTHER, classify(496));
        assertEquals(IntegrationHttpStatusClass.SERVER_ERROR, classify(500));
        assertEquals(IntegrationHttpStatusClass.SERVER_ERROR, classify(599));
        assertEquals(IntegrationHttpStatusClass.OTHER, classify(600));
    }

    // ==================== 指标标签契约 ====================

    @Test
    @DisplayName("分类名可直接用作低基数指标标签")
    void classificationNamesAreStableTagValues() {
        // 标签值即枚举名，一旦重命名就会让历史指标断层。
        assertEquals("SUCCESS", classify(200).name());
        assertEquals("CLIENT_ERROR", classify(404).name());
        assertEquals("SERVER_ERROR", classify(500).name());
        assertEquals("OTHER", classify(302).name());
    }

    @Test
    @DisplayName("全枚举共八个取值，分类不会产生新值")
    void enumHasExactlyEightValues() {
        assertEquals(8, IntegrationHttpStatusClass.values().length);
    }
}
