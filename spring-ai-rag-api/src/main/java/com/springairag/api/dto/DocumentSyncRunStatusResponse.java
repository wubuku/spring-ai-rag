package com.springairag.api.dto;

import java.util.List;

public record DocumentSyncRunStatusResponse(
        List<DocumentSyncRunResponse> runs,
        long total,
        int page,
        int size) {
}
