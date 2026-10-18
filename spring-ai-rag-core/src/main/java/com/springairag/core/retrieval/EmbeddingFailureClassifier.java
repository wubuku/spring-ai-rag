package com.springairag.core.retrieval;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

public final class EmbeddingFailureClassifier {
    public enum Kind { NONE, TRANSIENT, REJECTED, UNKNOWN }

    private EmbeddingFailureClassifier() { }

    public static Kind classify(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Kind fallback = Kind.UNKNOWN;
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16 && seen.add(current); depth++) {
            if (current instanceof com.springairag.core.admission.DependencyCallException
                    || current instanceof com.springairag.core.admission.AdmissionRejectedException) {
                return Kind.TRANSIENT;
            }
            if (current instanceof RestClientResponseException response) {
                int status = response.getStatusCode().value();
                if (status == 408 || status == 425 || status == 429 || status >= 500 && status <= 599) {
                    return Kind.TRANSIENT;
                }
                if (status >= 400 && status <= 499) {
                    return Kind.REJECTED;
                }
            }
            if (current instanceof ResourceAccessException || current instanceof IOException
                    || current instanceof TimeoutException) {
                return Kind.TRANSIENT;
            }
            if (fallback == Kind.UNKNOWN) {
                if (current instanceof TransientAiException) {
                    fallback = Kind.TRANSIENT;
                } else if (current instanceof NonTransientAiException) {
                    fallback = Kind.REJECTED;
                }
            }
            current = current.getCause();
        }
        return fallback;
    }
}
