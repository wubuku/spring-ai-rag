package com.springairag.core.service;

import com.springairag.api.dto.ClientErrorRequest;
import com.springairag.core.entity.RagClientError;
import com.springairag.core.repository.RagClientErrorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ClientErrorServiceImpl Unit Tests
 */
@ExtendWith(MockitoExtension.class)
class ClientErrorServiceImplTest {

    @Mock
    private RagClientErrorRepository clientErrorRepository;

    private ClientErrorServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ClientErrorServiceImpl(clientErrorRepository);
    }

    @Test
    void recordError_savesAllFields() {
        ClientErrorRequest request = new ClientErrorRequest();
        request.setErrorType("TypeError");
        request.setErrorMessage("Cannot read properties of undefined");
        request.setStackTrace("TypeError: Cannot read properties of undefined\n    at handleClick (app.js:123:45)");
        request.setComponentStack("ErrorBoundary\nChatPanel");
        request.setPageUrl("/webui/chat");
        request.setSessionId("session-abc123");
        request.setUserId("user-42");
        request.setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)");

        service.recordError(request);

        ArgumentCaptor<RagClientError> captor = ArgumentCaptor.forClass(RagClientError.class);
        verify(clientErrorRepository).save(captor.capture());

        RagClientError saved = captor.getValue();
        assertEquals("TypeError", saved.getErrorType());
        assertEquals("Cannot read properties of undefined", saved.getErrorMessage());
        assertEquals("TypeError: Cannot read properties of undefined\n    at handleClick (app.js:123:45)", saved.getStackTrace());
        assertEquals("ErrorBoundary\nChatPanel", saved.getComponentStack());
        assertEquals("/webui/chat", saved.getPageUrl());
        assertEquals("session-abc123", saved.getSessionId());
        assertEquals("user-42", saved.getUserId());
        assertEquals("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)", saved.getUserAgent());
    }

    @Test
    void recordError_minimalRequest_savesRequiredFieldsOnly() {
        ClientErrorRequest request = new ClientErrorRequest();
        request.setErrorType("Error");
        request.setErrorMessage("Something went wrong");
        // All optional fields are null

        service.recordError(request);

        ArgumentCaptor<RagClientError> captor = ArgumentCaptor.forClass(RagClientError.class);
        verify(clientErrorRepository).save(captor.capture());

        RagClientError saved = captor.getValue();
        assertEquals("Error", saved.getErrorType());
        assertEquals("Something went wrong", saved.getErrorMessage());
        assertNull(saved.getStackTrace());
        assertNull(saved.getPageUrl());
        assertNull(saved.getSessionId());
    }

    @Test
    void recordError_nullRequest_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.recordError(null));
        verify(clientErrorRepository, never()).save(any());
    }

    @Test
    void getErrorCount_delegatesToRepository() {
        when(clientErrorRepository.count()).thenReturn(42L);

        long count = service.getErrorCount();

        assertEquals(42L, count);
        verify(clientErrorRepository).count();
    }

    @Test
    void getErrorCount_emptyDatabase_returnsZero() {
        when(clientErrorRepository.count()).thenReturn(0L);

        long count = service.getErrorCount();

        assertEquals(0L, count);
    }

    @Test
    void recordError_savesOnce() {
        ClientErrorRequest request = new ClientErrorRequest();
        request.setErrorType("Error");
        request.setErrorMessage("test");

        service.recordError(request);

        verify(clientErrorRepository, times(1)).save(any());
    }

    @Test
    void recordError_repositoryThrowsException_propagatesAsRuntimeException() {
        ClientErrorRequest request = new ClientErrorRequest();
        request.setErrorType("Error");
        request.setErrorMessage("DB failure");
        when(clientErrorRepository.save(any(RagClientError.class)))
                .thenThrow(new RuntimeException("Database connection lost"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.recordError(request));
        assertEquals("Database connection lost", thrown.getMessage());
    }

    @Test
    void getErrorCount_repositoryThrowsException_propagatesAsRuntimeException() {
        when(clientErrorRepository.count())
                .thenThrow(new RuntimeException("Database unavailable"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.getErrorCount());
        assertEquals("Database unavailable", thrown.getMessage());
    }

    @Test
    void recordError_withLargeContent_savesAllFields() {
        ClientErrorRequest request = new ClientErrorRequest();
        request.setErrorType("RangeError");
        request.setErrorMessage("Stack overflow");
        // Stack trace exceeds 8192 characters
        request.setStackTrace("RangeError: Stack overflow\n    at " + "fn.js:1:1\n".repeat(1000));
        request.setComponentStack("App > Router > Component".repeat(500));
        request.setPageUrl("/webui/chat/session/12345");
        request.setSessionId("session-abc-def-123");
        request.setUserId("user-123456");
        request.setUserAgent("Mozilla/5.0 TestBrowser/1.0");

        service.recordError(request);

        ArgumentCaptor<RagClientError> captor = ArgumentCaptor.forClass(RagClientError.class);
        verify(clientErrorRepository).save(captor.capture());
        RagClientError saved = captor.getValue();
        assertEquals("RangeError", saved.getErrorType());
        assertEquals("Stack overflow", saved.getErrorMessage());
        assertTrue(saved.getStackTrace().startsWith("RangeError: Stack overflow"));
        assertTrue(saved.getStackTrace().length() > 10000);
        assertTrue(saved.getComponentStack().length() > 5000);
        assertEquals("/webui/chat/session/12345", saved.getPageUrl());
        assertEquals("session-abc-def-123", saved.getSessionId());
        assertEquals("user-123456", saved.getUserId());
        assertEquals("Mozilla/5.0 TestBrowser/1.0", saved.getUserAgent());
    }
}
