package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

@Schema(description = "Single item result in batch deletion")
public record BatchDeleteItem(
        @Schema(description = "Document ID", example = "123")
        Long id,

        @Schema(description = "Deletion status: DELETED or NOT_FOUND", example = "DELETED")
        String status
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchDeleteItem that = (BatchDeleteItem) o;
        return Objects.equals(id, that.id) && Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, status);
    }

    @Override
    public String toString() {
        return "BatchDeleteItem{id=" + id + ", status='" + status + "'}";
    }
}
