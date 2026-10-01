package com.springairag.core.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code maskSensitiveDataKeepType} 的多敏感值与类型分类边界（Batch 764）。
 *
 * <p>该方法此前的实现找到第一个匹配就替换并返回，**其余敏感值原样留在输出里**：
 * {@code "token=aaa secret=bbb"} 出来是 {@code "[SENSITIVE:TOKEN] secret=bbb"}，
 * 凭据仍然明文可见。一个名字里写着 mask 的方法必须把该遮的全部遮掉。
 *
 * <p>既有测试全部是单敏感值断言，因此这条行为从未暴露。本类从两个方向钉住：
 * 多敏感值必须全部被标记；同一模式反复出现必须全部被标记。
 */
class SensitiveDataMaskingKeepTypeMultiValueTest {

    private static String keepType(String message) {
        return SensitiveDataMaskingConverter.maskSensitiveDataKeepType(message);
    }

    // ==================== 多敏感值必须全部脱敏 ====================

    @Test
    @DisplayName("不同类型的敏感值全部被标记，不只第一处")
    void differentSecretTypesAreAllMarked() {
        String result = keepType(
                "token=first-token-12345 secret=second-secret-67890");

        assertTrue(result.contains("[SENSITIVE:TOKEN]"), result);
        assertTrue(result.contains("[SENSITIVE:SECRET]"), result);
        assertFalse(result.contains("first-token-12345"),
                () -> "第一个敏感值仍明文：" + result);
        assertFalse(result.contains("second-secret-67890"),
                () -> "第二个敏感值仍明文：" + result);
    }

    @Test
    @DisplayName("密码与 apiKey 同时出现时两者都被标记")
    void passwordAndApiKeyAreBothMarked() {
        String result = keepType(
                "connect ok password=SuperSecret1 apiKey=sk-live-ABCDEFGHIJ done");

        assertTrue(result.contains("[SENSITIVE:PASSWORD]"), result);
        assertTrue(result.contains("[SENSITIVE:API_KEY]"), result);
        assertFalse(result.contains("SuperSecret1"), result);
        assertFalse(result.contains("sk-live-ABCDEFGHIJ"), result);
    }

    @Test
    @DisplayName("同一类型重复出现时每次都被标记")
    void repeatedSameTypeIsMarkedEveryTime() {
        String result = keepType("token=aaa11111 then token=bbb22222 then token=ccc33333");

        assertEquals(3, countOccurrences(result, "[SENSITIVE:TOKEN]"),
                () -> "三次出现都必须被标记，实际：" + result);
        assertFalse(result.contains("aaa11111"), result);
        assertFalse(result.contains("bbb22222"), result);
        assertFalse(result.contains("ccc33333"), result);
    }

    @Test
    @DisplayName("中文身份证与手机号同时出现时都被标记")
    void chinesePiiAreBothMarked() {
        String result = keepType(
                "联系人 110101199003078515 手机 13800138000 提交成功");

        assertTrue(result.contains("[SENSITIVE:NATIONAL_ID]"), result);
        assertTrue(result.contains("[SENSITIVE:PHONE]"), result);
        assertFalse(result.contains("110101199003078515"), result);
        assertFalse(result.contains("13800138000"), result);
    }

    @Test
    @DisplayName("非敏感上下文原样保留")
    void nonSensitiveTextIsUntouched() {
        String message = "GET /api/v1/rag/collections 200 in 42ms";
        assertEquals(message, keepType(message));
    }

    @Test
    @DisplayName("null 与空串透传")
    void nullAndEmptyPassThrough() {
        assertEquals(null, keepType(null));
        assertEquals("", keepType(""));
    }

    // ==================== 类型分类链的边界 ====================

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "password=hunter2secret                 , PASSWORD",
            "passwd=hunter2secret                  , PASSWORD",
            "pwd=hunter2secret                     , PASSWORD",
            "apiKey=abcdefghij1234567890           , API_KEY",
            "api_key=abcdefghij1234567890          , API_KEY",
            "apikey=abcdefghij1234567890           , API_KEY",
            "accessToken=abcdefghij1234567890       , TOKEN",
            "secretKey=abcdefghij1234567890         , SECRET",
            "secret_key=abcdefghij1234567890        , SECRET",
    })
    @DisplayName("具名敏感字段投影出对应类型")
    void namedFieldsProjectTheirType(String message, String expectedType) {
        String result = keepType(message);

        assertTrue(result.contains("[SENSITIVE:" + expectedType + "]"),
                () -> "期望类型 " + expectedType + "，实际：" + result);
    }

    @Test
    @DisplayName("Authorization 头投影为 AUTH，且值本身不残留")
    void authorizationHeaderProjectsAuth() {
        String result = keepType("{\"authorization\":\"whatever-value-1234567890\"}");

        assertTrue(result.contains("[SENSITIVE:AUTH]"), result);
        assertFalse(result.contains("whatever-value-1234567890"),
                () -> "凭据值仍明文：" + result);
    }

    @Test
    @DisplayName("查询串中的 authorization 参数也被遮蔽")
    void authorizationQueryParameterIsMasked() {
        String result = keepType("GET /api?authorization=abcdef1234567890&page=2");

        assertFalse(result.contains("abcdef1234567890"),
                () -> "查询串凭据仍明文：" + result);
        assertTrue(result.contains("page=2"),
                () -> "非敏感参数必须保留：" + result);
    }

    @Test
    @DisplayName("AWS Access Key 投影为 AWS_KEY")
    void awsAccessKeyProjectsAwsKey() {
        String result = keepType("used AKIAIOSFODNN7EXAMPLE here");

        assertTrue(result.contains("[SENSITIVE:AWS_KEY]"), result);
        assertFalse(result.contains("AKIAIOSFODNN7EXAMPLE"), result);
    }

    @Test
    @DisplayName("无法归类的高熵值仍然被遮蔽，只是类型退化为通用标记")
    void unclassifiableValueStillGetsMasked() {
        // 兜底类型不精确是可接受的；漏遮是不可接受的。
        String result = keepType("key=Zm9vYmFyYmF6cXV1eGNvcmdlZ3JhdWx0");

        assertFalse(result.contains("Zm9vYmFyYmF6cXV1eGNvcmdlZ3JhdWx0"),
                () -> "高熵 key 必须被遮蔽，实际：" + result);
        assertTrue(result.contains("[SENSITIVE:"), result);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
