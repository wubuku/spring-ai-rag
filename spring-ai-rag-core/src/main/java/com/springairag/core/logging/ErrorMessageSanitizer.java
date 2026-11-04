package com.springairag.core.logging;

/**
 * 错误详情脱敏：掩掉密钥，并去掉内部类型名与堆栈帧。
 *
 * <p>只用于 5xx。4xx 的 detail 是写给调用方改请求的，原样保留。放在 logging
 * 包是因为底层复用 {@link SensitiveDataMaskingConverter} 的掩码规则；错误体
 * 出口（异常处理器）本身已在包图基线内依赖该包，不会引入新的跨包边。</p>
 */
public final class ErrorMessageSanitizer {

    private ErrorMessageSanitizer() {
    }

    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String masked = SensitiveDataMaskingConverter.maskSensitiveData(raw);
        // 先处理完整堆栈帧（at com.foo.Bar.method(Bar.java:42)），再处理裸类型名；
        // 顺序反过来的话，帧里的类名会先被替换，帧正则就匹配不到了。
        masked = masked.replaceAll(
                "\\bat\\s+[A-Za-z_$][A-Za-z0-9_$.]*(?:\\.[A-Za-z0-9_$<>]+)?\\([^)]*\\)",
                "<internal-frame>");
        // 完全限定类名：com./org./java./javax./jakarta. 开头的内部类型。
        masked = masked.replaceAll(
                "\\b(?:com|org|java|javax|jakarta)\\.(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+"
                        + "[A-Za-z_$][A-Za-z0-9_$]*",
                "<internal-type>");
        // 残留的源码定位（Foo.java:42），帧正则未匹配到的形态。
        masked = masked.replaceAll(
                "\\b[A-Za-z_$][A-Za-z0-9_$]*\\.java:\\d+", "<internal-source>");
        return masked;
    }
}
