package com.springairag.core.service;

import com.springairag.api.dto.ClientErrorRequest;

/**
 * Service for recording client-side errors reported from the WebUI.
 */
public interface ClientErrorService {

    /**
     * Record a client-side error from the WebUI.
     *
     * @param request the client error details
     */
    void recordError(ClientErrorRequest request);

    /**
     * Get the count of recorded errors.
     */
    long getErrorCount();
}
