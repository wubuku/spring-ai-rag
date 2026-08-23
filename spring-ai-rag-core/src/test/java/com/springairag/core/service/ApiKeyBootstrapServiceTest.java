package com.springairag.core.service;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyBootstrapServiceTest {

    @Mock RagApiPrincipalRepository principalRepository;
    @Mock EnvironmentRootCredentialResolver rootResolver;
    @Mock ApplicationArguments arguments;

    @Test
    void rootModeDoesNotInspectOrCreateDatabaseCredentials() {
        when(rootResolver.isConfigured()).thenReturn(true);
        new ApiKeyBootstrapService(principalRepository, rootResolver).run(arguments);
        verifyNoInteractions(principalRepository);
    }

    @Test
    void legacyModeOnlyAuditsUsableAdminPresence() {
        when(rootResolver.isConfigured()).thenReturn(false);
        when(principalRepository.countUsableByRole(
                eq(ApiKeyRole.ADMIN), any(LocalDateTime.class))).thenReturn(0L);

        new ApiKeyBootstrapService(principalRepository, rootResolver).run(arguments);

        verify(principalRepository).countUsableByRole(
                eq(ApiKeyRole.ADMIN), any(LocalDateTime.class));
        verifyNoMoreInteractions(principalRepository);
    }
}
