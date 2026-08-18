package com.springairag.core.retrieval;

/**
 * 检索诊断 HTTP 头。与请求级 {@code X-Trace-Id} 相互独立。
 */
public final class RetrievalTraceHeaders {

    public static final String TRACE_ID = "X-RAG-Retrieval-Trace-Id";

    public static final String OPERATION_SEARCH = "SEARCH";
    public static final String OPERATION_CHAT = "CHAT";
    public static final String OPERATION_OPENAI_CHAT = "OPENAI_CHAT";
    public static final String OPERATION_JSON_SEARCH = "JSON_SEARCH";

    public static final String REDACTED_QUERY = "[redacted]";

    private RetrievalTraceHeaders() {
    }
}
