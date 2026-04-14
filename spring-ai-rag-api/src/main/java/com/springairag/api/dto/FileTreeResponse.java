package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

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
}
