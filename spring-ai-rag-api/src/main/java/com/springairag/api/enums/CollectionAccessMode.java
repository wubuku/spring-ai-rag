package com.springairag.api.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前 API principal 的 Collection 访问模式。
 */
@Schema(
        name = "CollectionAccessMode",
        description = "当前 API principal 的 Collection 访问模式",
        enumAsRef = true)
public enum CollectionAccessMode {

    /**
     * 只能访问 principal policy 中明确列出的 Collection。
     */
    RESTRICTED,

    /**
     * 不受 Collection allow-list 限制。
     */
    UNRESTRICTED
}
