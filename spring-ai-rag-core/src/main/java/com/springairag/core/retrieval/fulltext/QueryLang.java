package com.springairag.core.retrieval.fulltext;

/**
 * Query language enumeration.
 *
 * <p>Used to select different full-text search strategies based on the language of the query content.
 */
public enum QueryLang {
    /** Chinese (includes CJK Unified Ideographs) */
    ZH,

    /** English or other languages */
    EN_OR_OTHER
}
