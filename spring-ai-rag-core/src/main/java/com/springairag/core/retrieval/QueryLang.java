package com.springairag.core.retrieval;

/**
 * Query language classification for adaptive full-text search strategy.
 *
 * <p>Detecting the language of a query allows the system to select the most
 * appropriate text retrieval strategy (Chinese FTS, English FTS, or trigram
 * fallback) without explicit user configuration.
 */
public enum QueryLang {

    /** Chinese query (contains CJK Unified Ideographs characters). */
    ZH,

    /** English or other non-Chinese query. */
    EN_OR_OTHER
}
