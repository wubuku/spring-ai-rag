package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Batch JSON structured-record upsert request.
 */
@Schema(description = "Batch JSON structured-record upsert request")
public class JsonRecordBatchUpsertRequest {

    @NotEmpty
    @Valid
    private List<JsonRecordUpsertRequest> items;

    public JsonRecordBatchUpsertRequest() {
    }

    public List<JsonRecordUpsertRequest> getItems() {
        return items;
    }

    public void setItems(List<JsonRecordUpsertRequest> items) {
        this.items = items;
    }
}
