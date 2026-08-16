package com.springairag.api.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Collection 检索范围模式。
 */
@Schema(
        name = "CollectionScopeMode",
        description = "Collection 检索范围模式",
        enumAsRef = true)
public enum CollectionScopeMode {

    /**
     * 调用方默认可见范围。
     */
    CALLER_VISIBLE,

    /**
     * 调用方可见且当前归属于任意 Collection 的文档。
     */
    ANY_COLLECTION,

    /**
     * 调用方明确指定的 Collection 并集。
     */
    SELECTED_COLLECTIONS
}
