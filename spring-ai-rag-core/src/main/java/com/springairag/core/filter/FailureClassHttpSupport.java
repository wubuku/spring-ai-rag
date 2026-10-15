package com.springairag.core.filter;

import com.springairag.api.enums.FailureClass;
import jakarta.servlet.http.HttpServletResponse;

/** Shared native HTTP failure-class header helper. */
public final class FailureClassHttpSupport {

    public static final String HEADER = "X-RAG-Failure-Class";

    private FailureClassHttpSupport() {
    }

    public static void apply(HttpServletResponse response, int status, String errorCode) {
        if (response == null || status < 400 || response.containsHeader(HEADER)) {
            return;
        }
        response.setHeader(HEADER, FailureClass.from(status, errorCode).name());
    }
}
