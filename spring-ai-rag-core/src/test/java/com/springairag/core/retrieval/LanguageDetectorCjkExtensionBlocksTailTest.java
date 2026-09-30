package com.springairag.core.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 语言检测器 CJK 扩展区纯长尾（Batch 741，JaCoCo 驱动）：Extension
 * E / F / G 与 Compatibility Ideographs Supplement 四个补充区段的
 * 判定臂（61-75）。
 */
class LanguageDetectorCjkExtensionBlocksTailTest {

    private final LanguageDetector detector = new LanguageDetector();

    @Test
    void extensionECodePointIsDetectedAsChinese() {
        // U+2A700 = CJK Unified Ideographs Extension E 首码位。
        String text = new String(Character.toChars(0x2A700));
        assertEquals(QueryLang.ZH, detector.detect(text));
    }

    @Test
    void extensionFCodePointIsDetectedAsChinese() {
        // U+2B740 = CJK Unified Ideographs Extension F 首码位。
        String text = new String(Character.toChars(0x2B740));
        assertEquals(QueryLang.ZH, detector.detect(text));
    }

    @Test
    void extensionGCodePointIsDetectedAsChinese() {
        // U+2B820 = CJK Unified Ideographs Extension G 首码位。
        String text = new String(Character.toChars(0x2B820));
        assertEquals(QueryLang.ZH, detector.detect(text));
    }

    @Test
    void compatibilitySupplementCodePointIsDetectedAsChinese() {
        // U+2F800 = CJK Compatibility Ideographs Supplement 首码位。
        String text = new String(Character.toChars(0x2F800));
        assertEquals(QueryLang.ZH, detector.detect(text));
    }

    @Test
    void englishTextFallsBackToEnOrOther() {
        assertEquals(QueryLang.EN_OR_OTHER,
                detector.detect("plain english query"));
    }
}
