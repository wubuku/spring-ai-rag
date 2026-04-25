package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * A single entry in a file tree listing (either a file or a synthetic directory).
 */
@Schema(description = "A single entry in the file tree (file or directory)")
public record FileTreeEntryResponse(

        @Schema(description = "Entry name (last path segment)", example = "default.md")
        String name,

        @Schema(description = "Full virtual path of the entry", example = "abc123/default.md")
        String path,

        @Schema(description = "Entry type: 'file' or 'directory'", example = "file")
        String type,

        @Schema(description = "MIME type (null for directories)", example = "text/markdown", nullable = true)
        String mimeType,

        @Schema(description = "File size in bytes (0 for directories)", example = "2048")
        long size
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileTreeEntryResponse that = (FileTreeEntryResponse) o;
        return size == that.size &&
                Objects.equals(name, that.name) &&
                Objects.equals(path, that.path) &&
                Objects.equals(type, that.type) &&
                Objects.equals(mimeType, that.mimeType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, path, type, mimeType, size);
    }

    @Override
    public String toString() {
        return "FileTreeEntryResponse{" +
                "name='" + name + '\'' +
                ", path='" + path + '\'' +
                ", type='" + type + '\'' +
                ", mimeType='" + mimeType + '\'' +
                ", size=" + size +
                '}';
    }
}
