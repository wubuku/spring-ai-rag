package com.springairag.api.enums;

/** 文档派生处理的固定阶段顺序。 */
public enum DocumentProcessingStageName {
    PARSING(1),
    CHUNKING(2),
    EMBEDDING(3),
    INDEXING(4),
    FINALIZING(5);

    private final int order;

    DocumentProcessingStageName(int order) {
        this.order = order;
    }

    public int order() {
        return order;
    }
}
