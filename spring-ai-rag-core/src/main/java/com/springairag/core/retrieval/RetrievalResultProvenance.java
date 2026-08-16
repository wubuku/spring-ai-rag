package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;

import java.util.Map;

/**
 * Maps authoritative document provenance onto retrieval results.
 */
public final class RetrievalResultProvenance {

    private static final String PDF_SOURCE_PREFIX = "pdf-import:";
    private static final String PDF_ENTRY_FILENAME = "default.md";
    private static final String PDF_ORIGINAL_FILENAME = "original.pdf";

    private RetrievalResultProvenance() {
    }

    public static void applyDocumentFields(
            RetrievalResult result, Map<String, Object> row) {
        String source = text(row.get("document_source"));
        String originalFilename = text(row.get("original_filename"));
        String documentTitle = text(row.get("document_title"));

        result.setSource(source);
        result.setTitle(documentTitle != null
                ? documentTitle
                : metadataTitle(result.getMetadata()));

        String indexedFilePath = safePdfIndexedPath(source);
        if (indexedFilePath == null) {
            result.setOriginalFilename(originalFilename);
            return;
        }

        int separator = indexedFilePath.lastIndexOf('/');
        String directoryPath = indexedFilePath.substring(0, separator + 1);
        result.setFileDirectoryPath(directoryPath);
        result.setIndexedFilePath(indexedFilePath);
        result.setOriginalFilePath(directoryPath + PDF_ORIGINAL_FILENAME);
        result.setOriginalFilename(isMarkdownPlaceholder(
                originalFilename, indexedFilePath) ? null : originalFilename);
    }

    public static void copy(
            RetrievalResult source, RetrievalResult target) {
        target.setSource(source.getSource());
        target.setOriginalFilename(source.getOriginalFilename());
        target.setFileDirectoryPath(source.getFileDirectoryPath());
        target.setIndexedFilePath(source.getIndexedFilePath());
        target.setOriginalFilePath(source.getOriginalFilePath());
    }

    private static String metadataTitle(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        return text(metadata.get("title"));
    }

    private static String safePdfIndexedPath(String source) {
        if (source == null || !source.startsWith(PDF_SOURCE_PREFIX)) {
            return null;
        }
        String path = source.substring(PDF_SOURCE_PREFIX.length());
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0
                || containsControlCharacter(path)) {
            return null;
        }
        String[] segments = path.split("/", -1);
        if (segments.length < 2) {
            return null;
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return null;
            }
        }
        if (!PDF_ENTRY_FILENAME.equals(segments[segments.length - 1])) {
            return null;
        }
        return path;
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMarkdownPlaceholder(
            String originalFilename, String indexedFilePath) {
        if (originalFilename == null) {
            return true;
        }
        return originalFilename.equals(indexedFilePath)
                || originalFilename.endsWith("/" + PDF_ENTRY_FILENAME);
    }

    private static String text(Object value) {
        if (!(value instanceof String stringValue)) {
            return null;
        }
        String normalized = stringValue.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
