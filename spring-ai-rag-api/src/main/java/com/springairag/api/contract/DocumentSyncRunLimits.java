package com.springairag.api.contract;

/**
 * Document Sync Run 公共请求与分页上限。
 *
 * <p>这些值同时用于 Jakarta validation、OpenAPI、服务守卫和机器可读能力合同，
 * 避免不同入口复制 magic number。</p>
 */
public final class DocumentSyncRunLimits {

    public static final int MAX_BATCH_ITEMS = 100;
    public static final int DEFAULT_ITEM_RECEIPT_PAGE_ITEMS = 100;
    public static final int MAX_ITEM_RECEIPT_PAGE_ITEMS = 200;
    public static final int DEFAULT_RUN_LIST_PAGE_ITEMS = 20;
    public static final int MAX_RUN_LIST_PAGE_ITEMS = 100;

    public static final String MAX_ITEM_RECEIPT_PAGE_ITEMS_TEXT = "200";
    public static final String DEFAULT_ITEM_RECEIPT_PAGE_ITEMS_TEXT = "100";
    public static final String DEFAULT_RUN_LIST_PAGE_ITEMS_TEXT = "20";
    public static final String MAX_RUN_LIST_PAGE_ITEMS_TEXT = "100";

    private DocumentSyncRunLimits() {
    }
}
