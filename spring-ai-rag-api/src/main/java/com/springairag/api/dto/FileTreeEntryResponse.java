package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
}
