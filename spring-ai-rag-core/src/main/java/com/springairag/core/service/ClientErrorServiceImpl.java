package com.springairag.core.service;

import com.springairag.api.dto.ClientErrorRequest;
import com.springairag.core.entity.RagClientError;
import com.springairag.core.repository.RagClientErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientErrorServiceImpl implements ClientErrorService {

    private static final Logger log = LoggerFactory.getLogger(ClientErrorServiceImpl.class);

    private final RagClientErrorRepository clientErrorRepository;

    public ClientErrorServiceImpl(RagClientErrorRepository clientErrorRepository) {
        this.clientErrorRepository = clientErrorRepository;
    }

    @Override
    @Transactional
    public void recordError(ClientErrorRequest request) {
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

    @Override
    public long getErrorCount() {
        return clientErrorRepository.count();
    }
}
