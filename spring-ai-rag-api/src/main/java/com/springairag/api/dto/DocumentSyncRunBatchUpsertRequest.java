package com.springairag.api.dto;

import com.springairag.api.contract.DocumentSyncRunLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record DocumentSyncRunBatchUpsertRequest(
        @NotEmpty
        @Size(max = DocumentSyncRunLimits.MAX_BATCH_ITEMS)
        List<@Valid DocumentSyncRunItemRequest> items) {
}
