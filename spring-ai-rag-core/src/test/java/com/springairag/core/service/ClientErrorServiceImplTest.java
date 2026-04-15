package com.springairag.core.service;

import com.springairag.api.dto.ClientErrorRequest;
import com.springairag.core.entity.RagClientError;
import com.springairag.core.repository.RagClientErrorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientErrorServiceImplTest {

    @Mock
    private RagClientErrorRepository clientErrorRepository;

    @InjectMocks
    private ClientErrorServiceImpl clientErrorService;

    @Test
    void recordError_savesAllFields() {
        ClientErrorRequest request = new ClientErrorRequest();
        request.setErrorType("Error");
        request.setErrorMessage("Test error message");
        request.setStackTrace("Error: Test error message\n    at test (test.js:1)");
        request.setComponentStack("Component\n    at render (Component.jsx:5)");
        request.setPageUrl("/webui/chat");
        request.setSessionId("session-123");
        request.setUserId("user-456");
        request.setUserAgent("Mozilla/5.0 TestBrowser");

        when(clientErrorRepository.save(any(RagClientError.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        clientErrorService.recordError(request);

        ArgumentCaptor<RagClientError> captor = ArgumentCaptor.forClass(RagClientError.class);
        verify(clientErrorRepository).save(captor.capture());

        RagClientError saved = captor.getValue();
        assertEquals("Error", saved.getErrorType());
        assertEquals("Test error message", saved.getErrorMessage());
        assertEquals("Error: Test error message\n    at test (test.js:1)", saved.getStackTrace());
        assertEquals("Component\n    at render (Component.jsx:5)", saved.getComponentStack());
        assertEquals("/webui/chat", saved.getPageUrl());
        assertEquals("session-123", saved.getSessionId());
        assertEquals("user-456", saved.getUserId());
        assertEquals("Mozilla/5.0 TestBrowser", saved.getUserAgent());
        // Note: createdAt is set by @PrePersist (JPA lifecycle) — not called in Mockito unit test
    }

    @Test
    void recordError_minimalRequest_savesSuccessfully() {
        ClientErrorRequest request = new ClientErrorRequest();
        request.setErrorType("TypeError");
        request.setErrorMessage("Cannot read properties of undefined");

        when(clientErrorRepository.save(any(RagClientError.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        clientErrorService.recordError(request);

        ArgumentCaptor<RagClientError> captor = ArgumentCaptor.forClass(RagClientError.class);
        verify(clientErrorRepository).save(captor.capture());

        RagClientError saved = captor.getValue();
        assertEquals("TypeError", saved.getErrorType());
        assertEquals("Cannot read properties of undefined", saved.getErrorMessage());
        assertNull(saved.getStackTrace());
        assertNull(saved.getComponentStack());
        assertNull(saved.getPageUrl());
        assertNull(saved.getSessionId());
        assertNull(saved.getUserId());
        assertNull(saved.getUserAgent());
    }

    @Test
    void getErrorCount_returnsRepositoryCount() {
        when(clientErrorRepository.count()).thenReturn(99L);

        long count = clientErrorService.getErrorCount();

        assertEquals(99L, count);
        verify(clientErrorRepository).count();
    }

    @Test
    void getErrorCount_emptyDatabase_returnsZero() {
        when(clientErrorRepository.count()).thenReturn(0L);

        long count = clientErrorService.getErrorCount();

        assertEquals(0L, count);
    }

    @Test
    void recordError_nullRequest_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> clientErrorService.recordError(null)
        );
        assertEquals("ClientErrorRequest must not be null", ex.getMessage());
        verify(clientErrorRepository, never()).save(any());
    }
}
