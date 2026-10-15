package com.springairag.core.service;

import com.springairag.api.dto.ClientErrorRequest;
import com.springairag.core.entity.RagClientError;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
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
        error.setErrorType(mask(request.getErrorType()));
        error.setErrorMessage(mask(request.getErrorMessage()));
        error.setStackTrace(mask(request.getStackTrace()));
        error.setComponentStack(mask(request.getComponentStack()));
        error.setPageUrl(mask(request.getPageUrl()));
        error.setSessionId(request.getSessionId());
        error.setUserId(request.getUserId());
        error.setUserAgent(mask(request.getUserAgent()));

        clientErrorRepository.save(error);

        log.warn("Client error recorded: type={}, message={}, page={}, session={}",
                error.getErrorType(),
                error.getErrorMessage(),
                error.getPageUrl(),
                request.getSessionId());
    }

    private String mask(String value) {
        return SensitiveDataMaskingConverter.maskSensitiveData(value);
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
