package com.springairag.core.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.resilience.LlmCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmbeddingFailureClassifierTest {
    @ParameterizedTest
    @ValueSource(ints = {408, 425, 429, 500, 502, 503, 504, 599})
    void temporaryHttpFailuresAreRetryable(int status) {
        assertEquals(EmbeddingFailureClassifier.Kind.TRANSIENT,
                EmbeddingFailureClassifier.classify(http(status)));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void providerRejectionsAreNotFastRetries(int status) {
        assertEquals(EmbeddingFailureClassifier.Kind.REJECTED,
                EmbeddingFailureClassifier.classify(http(status)));
    }

    @Test
    void specificCausesOverrideAiWrappersWithoutReadingMessages() {
        assertEquals(EmbeddingFailureClassifier.Kind.TRANSIENT,
                EmbeddingFailureClassifier.classify(new NonTransientAiException("fixture", http(503))));
        assertEquals(EmbeddingFailureClassifier.Kind.REJECTED,
                EmbeddingFailureClassifier.classify(new TransientAiException("fixture", http(401))));
        assertEquals(EmbeddingFailureClassifier.Kind.TRANSIENT,
                EmbeddingFailureClassifier.classify(new RuntimeException(new IOException())));
        assertEquals(EmbeddingFailureClassifier.Kind.UNKNOWN,
                EmbeddingFailureClassifier.classify(new IllegalStateException("503 is not an HTTP status")));
        assertEquals(EmbeddingFailureClassifier.Kind.UNKNOWN, EmbeddingFailureClassifier.classify(null));
    }

    @Test
    void cyclicAndDeepCausesAreBounded() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        first.initCause(second);
        second.initCause(first);
        assertEquals(EmbeddingFailureClassifier.Kind.UNKNOWN, EmbeddingFailureClassifier.classify(first));
        Throwable deep = http(503);
        for (int depth = 0; depth < 20; depth++) {
            deep = new RuntimeException(deep);
        }
        assertEquals(EmbeddingFailureClassifier.Kind.UNKNOWN, EmbeddingFailureClassifier.classify(deep));
    }

    @Test
    void fallbackPreservesSafeClassificationAndExistingResultJson() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.call(any(EmbeddingRequest.class))).thenThrow(http(503));
        when(model.embed(anyString())).thenThrow(http(503));
        var result = new EmbeddingBatchService(model, 10).createEmbeddingsBatch(List.of("fixture")).get(0);
        assertFalse(result.isSuccess());
        assertEquals(EmbeddingFailureClassifier.Kind.TRANSIENT, result.getFailureKind());
        var compatible = new EmbeddingBatchService.EmbeddingResult(result.getText(), result.getEmbedding(), result.getError());
        assertEquals(compatible, result);
        assertEquals(compatible.hashCode(), result.hashCode());
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.valueToTree(compatible), mapper.valueToTree(result));
        assertFalse(mapper.readTree(mapper.writeValueAsString(result)).has("failureKind"));
    }

    @Test
    void circuitBreakerAndMissingVectorHaveDistinctKinds() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        LlmCircuitBreaker breaker = mock(LlmCircuitBreaker.class);
        when(breaker.allowCall()).thenReturn(false);
        var rejected = new EmbeddingBatchService(model, 10, breaker).createEmbeddingsBatch(List.of("fixture"));
        assertEquals(EmbeddingFailureClassifier.Kind.TRANSIENT, rejected.get(0).getFailureKind());
        verifyNoInteractions(model);
        when(model.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(List.of()));
        var missing = new EmbeddingBatchService(model, 10).createEmbeddingsBatch(List.of("fixture"));
        assertFalse(missing.get(0).isSuccess());
        assertEquals(EmbeddingFailureClassifier.Kind.NONE, missing.get(0).getFailureKind());
    }

    private RestClientResponseException http(int status) {
        return new RestClientResponseException("private-provider-fixture", status, "fixture",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }
}
