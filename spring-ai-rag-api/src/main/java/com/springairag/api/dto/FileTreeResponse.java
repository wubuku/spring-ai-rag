package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

/**
 * Response payload for the GET /files/tree endpoint.
 */
@Schema(description = "Directory listing response with path and entries")
public record FileTreeResponse(

        @Schema(description = "The virtual path that was listed (ends with '/' or is '/')", example = "abc123/")
        String path,

        @Schema(description = "List of direct children (files and directories)")
        List<FileTreeEntryResponse> entries,

        @Schema(description = "Total number of entries returned", example = "2")
        int total
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileTreeResponse that = (FileTreeResponse) o;
        return total == that.total &&
                Objects.equals(path, that.path) &&
                Objects.equals(entries, that.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, entries, total);
    }

    @Override
    public String toString() {
        return "FileTreeResponse{" +
                "path='" + path + '\'' +
                ", entries=" + (entries != null ? entries.size() + " entry(ies)" : "null") +
                ", total=" + total +
                '}';
    }
}
