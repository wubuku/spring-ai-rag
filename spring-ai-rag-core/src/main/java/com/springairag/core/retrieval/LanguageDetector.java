package com.springairag.core.retrieval;

import org.springframework.stereotype.Component;

/**
 * Lightweight language detector for query text.
 *
 * <p>Uses Unicode block analysis to determine if a query contains Chinese
 * characters (CJK Unified Ideographs block). This is a heuristic — it does
 * not perform full language classification but is sufficient to distinguish
 * Chinese from English/other text for the purpose of selecting an FTS
 * strategy.
 *
 * <p>Why Unicode block instead of character ranges?
 * Unicode CJK blocks cover all CJK Unified Ideographs including extensions
 * (Extension A/B/C/D), allowing correct detection of uncommon characters
 * without maintaining a character dictionary.
 */
@Component
public class LanguageDetector {

    /**
     * Detect whether the given text is primarily Chinese or English/other.
     *
     * <p>Detection rule: if any code point falls within a CJK Unicode block,
     * the text is classified as Chinese. This catches both simplified and
     * traditional Chinese characters.
     *
     * @param text the query text to analyze (must not be null)
     * @return ZH if any CJK character is found, EN_OR_OTHER otherwise
     */
    public QueryLang detect(String text) {
        if (text == null || text.isEmpty()) {
            return QueryLang.EN_OR_OTHER;
        }
        boolean hasCjk = text.codePoints().anyMatch(this::isCjkBlock);
        return hasCjk ? QueryLang.ZH : QueryLang.EN_OR_OTHER;
    }

    /**
     * Check if a code point belongs to any CJK Unicode block.
     *
     * <p>For code points beyond the BMP (> U+FFFF), {@link Character.UnicodeBlock#of(int)}
     * may return {@link Character.UnicodeBlock#GENERAL_PURPOSE_PLANE_00} instead of the
     * specific extension block. Therefore we use direct range checks for supplementary
     * CJK extension blocks.
     */
    private boolean isCjkBlock(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
            return true;
        }
        // Supplementary CJK Extension blocks (beyond BMP) — use code point ranges
        // because Character.UnicodeBlock.of() does not reliably classify them.
        if (codePoint >= 0x20000 && codePoint <= 0x2A6DF) {
            // CJK Unified Ideographs Extension B–D
            return true;
        }
        if (codePoint >= 0x2A700 && codePoint <= 0x2B73F) {
            // CJK Unified Ideographs Extension E
            return true;
        }
        if (codePoint >= 0x2B740 && codePoint <= 0x2B81F) {
            // CJK Unified Ideographs Extension F
            return true;
        }
        if (codePoint >= 0x2B820 && codePoint <= 0x2CEAF) {
            // CJK Unified Ideographs Extension G
            return true;
        }
        if (codePoint >= 0x2F800 && codePoint <= 0x2FA1F) {
            // CJK Compatibility Ideographs Supplement
            return true;
        }
        return false;
    }
}
