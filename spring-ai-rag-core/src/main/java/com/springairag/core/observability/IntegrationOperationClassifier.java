package com.springairag.core.observability;

import com.springairag.api.enums.IntegrationOperation;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将稳定 HTTP method/path 映射为有限 operation 枚举。
 */
public final class IntegrationOperationClassifier {

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;
    private static final Map<String, IntegrationOperation> EXACT = Map.ofEntries(
            entry("GET", "/api/v1/rag/integration-capabilities",
                    IntegrationOperation.INTEGRATION_CAPABILITIES),
            entry("GET", "/api/v1/rag/auth/me",
                    IntegrationOperation.CURRENT_PRINCIPAL),
            entry("GET", "/api/v1/rag/collections/by-key",
                    IntegrationOperation.COLLECTION_LOOKUP),
            entry("GET", "/api/v1/rag/collections/embedding-readiness",
                    IntegrationOperation.COLLECTION_READINESS),
            entry("POST", "/api/v1/rag/json-records/upsert",
                    IntegrationOperation.JSON_RECORD_UPSERT),
            entry("POST", "/api/v1/rag/json-records/batch-upsert",
                    IntegrationOperation.JSON_RECORD_BATCH_UPSERT),
            entry("POST", "/api/v1/rag/json-records/search",
                    IntegrationOperation.JSON_RECORD_SEARCH),
            entry("GET", "/api/v1/rag/json-records/by-external-id",
                    IntegrationOperation.JSON_RECORD_LOOKUP),
            entry("DELETE", "/api/v1/rag/json-records/by-external-id",
                    IntegrationOperation.JSON_RECORD_TOMBSTONE),
            entry("POST", "/api/v1/rag/document-sync-runs",
                    IntegrationOperation.SYNC_RUN_BEGIN),
            entry("POST", "/api/v1/rag/document-sync-runs/{runId}/batch-upsert",
                    IntegrationOperation.SYNC_RUN_BATCH_UPSERT),
            entry("POST", "/api/v1/rag/document-sync-runs/{runId}/preview-missing",
                    IntegrationOperation.SYNC_RUN_PREVIEW),
            entry("POST", "/api/v1/rag/document-sync-runs/{runId}/complete",
                    IntegrationOperation.SYNC_RUN_COMPLETE),
            entry("POST", "/api/v1/rag/document-sync-runs/{runId}/abort",
                    IntegrationOperation.SYNC_RUN_ABORT),
            entry("GET", "/api/v1/rag/document-sync-runs/{runId}",
                    IntegrationOperation.SYNC_RUN_GET),
            entry("GET", "/api/v1/rag/document-sync-runs/{runId}/items",
                    IntegrationOperation.SYNC_RUN_ITEMS),
            entry("GET", "/api/v1/rag/document-sync-runs",
                    IntegrationOperation.SYNC_RUN_LIST));

    private static final List<Route> PATTERNS = EXACT.entrySet().stream()
            .filter(entry -> entry.getKey().contains("{"))
            .map(entry -> {
                String[] parts = entry.getKey().split(" ", 2);
                return new Route(
                        parts[0],
                        PARSER.parse(parts[1]),
                        entry.getValue());
            })
            .toList();

    private IntegrationOperationClassifier() {
    }

    public static IntegrationOperation classify(String method, String requestUri) {
        if (method == null || requestUri == null) {
            return null;
        }
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        String path = normalizePath(requestUri);
        IntegrationOperation exact = EXACT.get(normalizedMethod + " " + path);
        if (exact != null) {
            return exact;
        }
        PathContainer container = PathContainer.parsePath(path);
        return PATTERNS.stream()
                .filter(route -> route.method().equals(normalizedMethod)
                        && route.pattern().matches(container))
                .map(Route::operation)
                .findFirst()
                .orElse(null);
    }

    static String normalizePath(String requestUri) {
        String path = requestUri;
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static Map.Entry<String, IntegrationOperation> entry(
            String method,
            String path,
            IntegrationOperation operation) {
        return Map.entry(method + " " + path, operation);
    }

    private record Route(
            String method,
            PathPattern pattern,
            IntegrationOperation operation) {
    }
}
