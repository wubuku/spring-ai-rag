package com.springairag.core.retrieval.fulltext;

/**
 * 查询语言枚举
 *
 * <p>用于根据查询内容的语言选择不同的全文检索策略。
 */
public enum QueryLang {
    /** 中文（包含 CJK 统一表意文字） */
    ZH,
    
    /** 英文或其他语言 */
    EN_OR_OTHER
}
