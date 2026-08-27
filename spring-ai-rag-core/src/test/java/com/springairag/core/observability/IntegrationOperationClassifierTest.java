package com.springairag.core.observability;

import com.springairag.api.enums.IntegrationOperation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IntegrationOperationClassifierTest {

    @ParameterizedTest
    @MethodSource("supportedRoutes")
    void classifiesEverySupportedRoute(
            String method,
            String path,
            IntegrationOperation operation) {
        assertEquals(
                operation,
                IntegrationOperationClassifier.classify(
                        method,
                        path + "?trace=ignored"));
        assertEquals(
                operation,
                IntegrationOperationClassifier.classify(method, path + "/"));
    }

    @ParameterizedTest
    @MethodSource("supportedRoutes")
    void rejectsWrongMethod(String method, String path, IntegrationOperation ignored) {
        String wrongMethod = switch (method) {
            case "GET" -> "PATCH";
            case "POST" -> "PUT";
            case "DELETE" -> "PATCH";
            default -> "TRACE";
        };
        assertNull(IntegrationOperationClassifier.classify(wrongMethod, path));
    }

    @ParameterizedTest
    @MethodSource("unknownRoutes")
    void unknownRoutesAreNoOp(String method, String path) {
        assertNull(IntegrationOperationClassifier.classify(method, path));
    }

    private static Stream<Arguments> supportedRoutes() {
        return Stream.of(
                Arguments.of("GET", "/api/v1/rag/integration-capabilities",
                        IntegrationOperation.INTEGRATION_CAPABILITIES),
                Arguments.of("GET", "/api/v1/rag/auth/me",
                        IntegrationOperation.CURRENT_PRINCIPAL),
                Arguments.of("GET", "/api/v1/rag/collections/by-key",
                        IntegrationOperation.COLLECTION_LOOKUP),
                Arguments.of("GET", "/api/v1/rag/collections/embedding-readiness",
                        IntegrationOperation.COLLECTION_READINESS),
                Arguments.of("POST", "/api/v1/rag/json-records/upsert",
                        IntegrationOperation.JSON_RECORD_UPSERT),
                Arguments.of("POST", "/api/v1/rag/json-records/batch-upsert",
                        IntegrationOperation.JSON_RECORD_BATCH_UPSERT),
                Arguments.of("POST", "/api/v1/rag/json-records/search",
                        IntegrationOperation.JSON_RECORD_SEARCH),
                Arguments.of("GET", "/api/v1/rag/json-records/by-external-id",
                        IntegrationOperation.JSON_RECORD_LOOKUP),
                Arguments.of("DELETE", "/api/v1/rag/json-records/by-external-id",
                        IntegrationOperation.JSON_RECORD_TOMBSTONE),
                Arguments.of("POST", "/api/v1/rag/document-sync-runs",
                        IntegrationOperation.SYNC_RUN_BEGIN),
                Arguments.of("POST", "/api/v1/rag/document-sync-runs/run-123/batch-upsert",
                        IntegrationOperation.SYNC_RUN_BATCH_UPSERT),
                Arguments.of("POST", "/api/v1/rag/document-sync-runs/run-123/preview-missing",
                        IntegrationOperation.SYNC_RUN_PREVIEW),
                Arguments.of("POST", "/api/v1/rag/document-sync-runs/run-123/complete",
                        IntegrationOperation.SYNC_RUN_COMPLETE),
                Arguments.of("POST", "/api/v1/rag/document-sync-runs/run-123/abort",
                        IntegrationOperation.SYNC_RUN_ABORT),
                Arguments.of("GET", "/api/v1/rag/document-sync-runs/run-123",
                        IntegrationOperation.SYNC_RUN_GET),
                Arguments.of("GET", "/api/v1/rag/document-sync-runs/run-123/items",
                        IntegrationOperation.SYNC_RUN_ITEMS),
                Arguments.of("GET", "/api/v1/rag/document-sync-runs",
                        IntegrationOperation.SYNC_RUN_LIST));
    }

    private static Stream<Arguments> unknownRoutes() {
        return Stream.of(
                Arguments.of("GET", "/api/v1/rag/unknown"),
                Arguments.of("POST", "/api/v1/rag/document-sync-runs/run-123"),
                Arguments.of("GET", "/api/v1/rag/document-sync-runs/run-123/unknown"),
                Arguments.of("GET", "/api/v1/rag/json-records/by-external-id/abc"),
                Arguments.of("GET", "/api/v1/rag/integration-observability"),
                Arguments.of(null, "/api/v1/rag/search"),
                Arguments.of("GET", null));
    }
}
