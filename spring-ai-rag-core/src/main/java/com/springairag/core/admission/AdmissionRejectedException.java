package com.springairag.core.admission;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;

public final class AdmissionRejectedException extends RagException {
    public AdmissionRejectedException() {
        super(ErrorCode.TOO_MANY_REQUESTS, "Instance capacity exhausted; retry later", Integer.valueOf(1));
    }

    public static void propagate(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof AdmissionRejectedException rejected) {
                throw rejected;
            }
            current = current.getCause();
        }
    }
}
