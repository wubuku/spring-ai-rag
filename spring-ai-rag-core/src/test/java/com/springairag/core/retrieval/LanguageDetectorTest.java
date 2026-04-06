package com.springairag.core.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LanguageDetector}.
 */
class LanguageDetectorTest {

    private final LanguageDetector detector = new LanguageDetector();

    // --- Chinese detection (ZH) ---

    @Test
    void detect_chineseSimplified_returnsZh() {
        assertEquals(QueryLang.ZH, detector.detect("痘痘"));
    }

    @Test
    void detect_chineseTraditional_returnsZh() {
        assertEquals(QueryLang.ZH, detector.detect("皮膚"));
    }

    @Test
    void detect_mixedChineseEnglish_returnsZh() {
        assertEquals(QueryLang.ZH, detector.detect("Spring AI RAG 检索"));
    }

    @Test
    void detect_chineseWithPunctuation_returnsZh() {
        assertEquals(QueryLang.ZH, detector.detect("如何治疗痘痘？"));
    }

    @Test
    void detect_chineseExtensionA_returnsZh() {
        // CJK Unified Ideographs Extension A starts at U+3400
        assertEquals(QueryLang.ZH, detector.detect("\u4DAE")); // U+4DAE = 㶎 (Extension A)
    }

    @Test
    void detect_chineseExtensionB_returnsZh() {
        // U+20000 is the first character of CJK Unified Ideographs Extension B (supplementary plane)
        assertEquals(QueryLang.ZH, detector.detect(new String(Character.toChars(0x20000))));
    }

    @Test
    void detect_chineseCompatibilityIdeographs_returnsZh() {
        // CJK Compatibility Ideographs block (U+F900–U+FAFF)
        assertEquals(QueryLang.ZH, detector.detect("\uFA0C")); // U+FA0C = 𪚥 (Compatibility)
    }

    // --- English / Other detection (EN_OR_OTHER) ---

    @Test
    void detect_english_returnsEn() {
        assertEquals(QueryLang.EN_OR_OTHER, detector.detect("Spring AI"));
    }

    @Test
    void detect_englishSentence_returnsEn() {
        assertEquals(QueryLang.EN_OR_OTHER,
            detector.detect("How do I configure pgvector in Spring Boot?"));
    }

    @Test
    void detect_numbersOnly_returnsEn() {
        assertEquals(QueryLang.EN_OR_OTHER, detector.detect("1024"));
    }

    @Test
    void detect_specialCharacters_returnsEn() {
        assertEquals(QueryLang.EN_OR_OTHER, detector.detect("!@#$%^&*()"));
    }

    @Test
    void detect_emptyString_returnsEn() {
        assertEquals(QueryLang.EN_OR_OTHER, detector.detect(""));
    }

    @Test
    void detect_null_returnsEn() {
        assertEquals(QueryLang.EN_OR_OTHER, detector.detect(null));
    }

    @Test
    void detect_whitespaceOnly_returnsEn() {
        assertEquals(QueryLang.EN_OR_OTHER, detector.detect("   \t\n  "));
    }

    // --- Edge cases ---

    @Test
    void detect_japaneseHiragana_returnsEn() {
        // Japanese Hiragana is NOT in CJK blocks
        assertEquals(QueryLang.EN_OR_OTHER, detector.detect("すもも"));
    }

    @Test
    void detect_japaneseKatakana_returnsEn() {
        // Japanese Katakana is NOT in CJK blocks
        assertEquals(QueryLang.EN_OR_OTHER, detector.detect(".Spring"));
    }

    @Test
    void detect_koreanHangul_returnsEn() {
        // Korean Hangul is in Hangul Jamo block, NOT CJK
        assertEquals(QueryLang.EN_OR_OTHER, detector.detect("봄"));
    }

    @Test
    void detect_russianCyrillic_returnsEn() {
        assertEquals(QueryLang.EN_OR_OTHER, detector.detect("Привет"));
    }

    @Test
    void detect_emoji_returnsEn() {
        assertEquals(QueryLang.EN_OR_OTHER, detector.detect("🚀"));
    }

    @Test
    void detect_singleChineseCharacter_returnsZh() {
        assertEquals(QueryLang.ZH, detector.detect("中"));
    }

    @Test
    void detect_mixedShortChineseWord_returnsZh() {
        assertEquals(QueryLang.ZH, detector.detect("检索"));
    }

    @Test
    void detect_queryLikeRagSearch_returnsZh() {
        assertEquals(QueryLang.ZH, detector.detect("RAG 检索增强生成"));
    }

    @Test
    void detect_technicalTermInChinese_returnsZh() {
        assertEquals(QueryLang.ZH, detector.detect("向量数据库 pgvector"));
    }
}
