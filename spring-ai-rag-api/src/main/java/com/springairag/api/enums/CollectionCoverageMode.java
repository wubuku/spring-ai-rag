package com.springairag.api.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Collection candidate coverage strategy.
 */
@Schema(
        name = "CollectionCoverageMode",
        description = "Collection candidate coverage strategy",
        enumAsRef = true)
public enum CollectionCoverageMode {

    /** Existing global top-k behavior. */
    GLOBAL,

    /** Give each selected Collection with a hit a bounded opportunity to contribute. */
    EACH_COLLECTION
}
