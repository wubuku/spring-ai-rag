package com.springairag.core.resilience;

import com.springairag.api.dto.FailureDetails;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;

public final class ClassifiedRagException extends RagException {
    private final FailureDetails details;

    public ClassifiedRagException(ErrorCode code, FailureDetails details) {
        super(code, "The Chat turn previously failed");
        this.details = details;
    }

    public FailureDetails details() {
        return details;
    }
}
