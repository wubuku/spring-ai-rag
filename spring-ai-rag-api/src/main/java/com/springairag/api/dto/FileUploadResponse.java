package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

/**
 * File upload and embed response
 */
@Schema(description = "File upload and embed response")
public record FileUploadResponse(
        @Schema(description = "Number of successfully processed documents", example = "10")
        int processed,

        @Schema(description = "Number of successful documents", example = "10")
        int success,

        @Schema(description = "Number of failed documents", example = "0")
        int failed,

        @Schema(description = "Processing results for each file")
        List<FileResult> results
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileUploadResponse that = (FileUploadResponse) o;
        return processed == that.processed &&
                success == that.success &&
                failed == that.failed &&
                Objects.equals(results, that.results);
    }

    @Override
    public int hashCode() {
        return Objects.hash(processed, success, failed, results);
    }

    @Override
    public String toString() {
        return "FileUploadResponse{" +
                "processed=" + processed +
                ", success=" + success +
                ", failed=" + failed +
                ", results=" + (results != null ? results.size() + " result(s)" : "null") +
                '}';
    }

    @Schema(description = "Single file processing result")
    public record FileResult(
            @Schema(description = "Original filename", example = "product-manual.txt")
            String filename,

            @Schema(description = "Document ID (on success)", example = "1")
            Long documentId,

            @Schema(description = "Document title", example = "Product Manual")
            String title,

            @Schema(description = "Whether successfully embedded", example = "true")
            boolean embedded,

            @Schema(description = "Number of chunks (on success)", example = "5")
            int chunks,

            @Schema(description = "Error message (on failure)")
            String error
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FileResult fileResult = (FileResult) o;
            return embedded == fileResult.embedded &&
                    chunks == fileResult.chunks &&
                    Objects.equals(filename, fileResult.filename) &&
                    Objects.equals(documentId, fileResult.documentId) &&
                    Objects.equals(title, fileResult.title) &&
                    Objects.equals(error, fileResult.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(filename, documentId, title, embedded, chunks, error);
        }

        @Override
        public String toString() {
            return "FileUploadResponse.FileResult{" +
                    "filename='" + filename + '\'' +
                    ", documentId=" + documentId +
                    ", title='" + title + '\'' +
                    ", embedded=" + embedded +
                    ", chunks=" + chunks +
                    ", error='" + error + '\'' +
                    '}';
        }
    }
}
