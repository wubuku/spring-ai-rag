package com.springairag.core.service;

import com.springairag.api.dto.ClientErrorRequest;
import com.springairag.core.entity.RagClientError;
import com.springairag.core.repository.RagClientErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Client Error Service Implementation
 *
 * <p>Records client-side errors reported by the WebUI via POST /client-errors endpoint.
 * These errors are stored in the rag_client_error table for debugging and analytics.
 * Error recording is best-effort: failures in recording do not propagate to the caller.
 */
@Service
public class ClientErrorServiceImpl implements ClientErrorService {

    private static final Logger log = LoggerFactory.getLogger(ClientErrorServiceImpl.class);

    private final RagClientErrorRepository clientErrorRepository;

    public ClientErrorServiceImpl(RagClientErrorRepository clientErrorRepository) {
        this.clientErrorRepository = clientErrorRepository;
    }

    /**
     * Records a client-side error to the database.
     *
     * @param request the client error details (error type, message, stack trace, etc.)
     * @throws IllegalArgumentException if request is null
     */
    @Override
    @Transactional
    public void recordError(ClientErrorRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ClientErrorRequest must not be null");
        }
        RagClientError error = new RagClientError();
        error.setErrorType(request.getErrorType());
        error.setErrorMessage(request.getErrorMessage());
        error.setStackTrace(request.getStackTrace());
        error.setComponentStack(request.getComponentStack());
        error.setPageUrl(request.getPageUrl());
        error.setSessionId(request.getSessionId());
        error.setUserId(request.getUserId());
        error.setUserAgent(request.getUserAgent());

        clientErrorRepository.save(error);

        log.warn("Client error recorded: type={}, message={}, page={}, session={}",
                request.getErrorType(),
                request.getErrorMessage(),
                request.getPageUrl(),
                request.getSessionId());
    }

    /**
     * Returns the total number of recorded client errors.
     *
     * @return total error count in the database
     */
    @Override
    public long getErrorCount() {
        return clientErrorRepository.count();
    }
}
