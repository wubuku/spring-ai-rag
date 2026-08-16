package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Batch request for stable-identity external document upserts.
 */
@Schema(description = "External document batch upsert request")
public class ExternalDocumentBatchUpsertRequest {

    @NotEmpty
    @Size(max = 50)
    private List<ExternalDocumentUpsertRequest> items;

    public List<ExternalDocumentUpsertRequest> getItems() { return items; }
    public void setItems(List<ExternalDocumentUpsertRequest> items) { this.items = items; }
}
