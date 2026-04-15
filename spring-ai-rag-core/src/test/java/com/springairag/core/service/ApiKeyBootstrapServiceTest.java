package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.repository.RagApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyBootstrapService Tests")
class ApiKeyBootstrapServiceTest {

    @Mock
    private ApiKeyManagementService apiKeyManagementService;

    @Mock
    private RagApiKeyRepository apiKeyRepository;

    @Mock
    private ApplicationArguments args;

    @Captor
    private ArgumentCaptor<RagApiKey> ragApiKeyCaptor;

    private ApiKeyBootstrapService bootstrapService;

    @BeforeEach
    void setUp() {
        bootstrapService = new ApiKeyBootstrapService(apiKeyManagementService, apiKeyRepository);
    }

    @Nested
    @DisplayName("run()")
    class RunTests {

        @Test
        @DisplayName("skips when keys already exist")
        void skipsWhenKeysExist() {
            when(apiKeyRepository.count()).thenReturn(5L);

            bootstrapService.run(args);

            verify(apiKeyManagementService, never()).generateKey(any());
            verify(apiKeyRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips when exactly one key exists")
        void skipsWhenOneKeyExists() {
            when(apiKeyRepository.count()).thenReturn(1L);

            bootstrapService.run(args);

            verify(apiKeyManagementService, never()).generateKey(any());
            verify(apiKeyRepository, never()).save(any());
        }

        @Test
        @DisplayName("generates admin key when no keys exist")
        void generatesAdminKeyWhenNoKeysExist() {
            when(apiKeyRepository.count()).thenReturn(0L);

            ApiKeyCreatedResponse generated = new ApiKeyCreatedResponse(
                    "rag_sk_test123", "sk_test_raw_key", "Admin Key (auto-generated)", null);
            when(apiKeyManagementService.generateKey(any(ApiKeyCreateRequest.class))).thenReturn(generated);

            RagApiKey ragApiKey = new RagApiKey();
            ragApiKey.setKeyId("rag_sk_test123");
            when(apiKeyRepository.findByKeyId("rag_sk_test123")).thenReturn(Optional.of(ragApiKey));

            bootstrapService.run(args);

            // Verify generateKey was called with correct request
            verify(apiKeyManagementService).generateKey(argThat(req ->
                    req.getName().equals("Admin Key (auto-generated)") && req.getExpiresAt() == null));

            // Verify save was called to upgrade role
            verify(apiKeyRepository).save(any(RagApiKey.class));
        }

        @Test
        @DisplayName("handles generateKey returning null gracefully")
        void handlesNullGenerateKeyResult() {
            when(apiKeyRepository.count()).thenReturn(0L);
            when(apiKeyManagementService.generateKey(any(ApiKeyCreateRequest.class))).thenReturn(null);

            // Should not throw, should complete without error
            bootstrapService.run(args);

            verify(apiKeyRepository, never()).findByKeyId(any());
            verify(apiKeyRepository, never()).save(any());
        }

        @Test
        @DisplayName("handles key not found after generation gracefully")
        void handlesKeyNotFoundAfterGeneration() {
            when(apiKeyRepository.count()).thenReturn(0L);

            ApiKeyCreatedResponse generated = new ApiKeyCreatedResponse(
                    "rag_sk_test456", "sk_test_raw_key", "Admin Key (auto-generated)", null);
            when(apiKeyManagementService.generateKey(any(ApiKeyCreateRequest.class))).thenReturn(generated);
            when(apiKeyRepository.findByKeyId("rag_sk_test456")).thenReturn(Optional.empty());

            // Should not throw, should complete without error
            bootstrapService.run(args);

            verify(apiKeyRepository, never()).save(any());
        }

        @Test
        @DisplayName("sets role to ADMIN for newly generated key")
        void setsAdminRoleCorrectly() {
            when(apiKeyRepository.count()).thenReturn(0L);

            ApiKeyCreatedResponse generated = new ApiKeyCreatedResponse(
                    "rag_sk_admin001", "sk_admin_raw", "Admin Key (auto-generated)", null);
            when(apiKeyManagementService.generateKey(any(ApiKeyCreateRequest.class))).thenReturn(generated);

            RagApiKey storedKey = new RagApiKey();
            storedKey.setKeyId("rag_sk_admin001");
            when(apiKeyRepository.findByKeyId("rag_sk_admin001")).thenReturn(Optional.of(storedKey));

            bootstrapService.run(args);

            verify(apiKeyRepository).save(ragApiKeyCaptor.capture());
            assertThat(ragApiKeyCaptor.getValue().getKeyId()).isEqualTo("rag_sk_admin001");
        }

        @Test
        @DisplayName("calls save with the correct key entity")
        void savesCorrectEntity() {
            when(apiKeyRepository.count()).thenReturn(0L);

            ApiKeyCreatedResponse generated = new ApiKeyCreatedResponse(
                    "rag_sk_entity_test", "sk_entity_raw", "Admin Key (auto-generated)", null);
            when(apiKeyManagementService.generateKey(any(ApiKeyCreateRequest.class))).thenReturn(generated);

            RagApiKey storedKey = new RagApiKey();
            storedKey.setKeyId("rag_sk_entity_test");
            when(apiKeyRepository.findByKeyId("rag_sk_entity_test")).thenReturn(Optional.of(storedKey));

            bootstrapService.run(args);

            ArgumentCaptor<RagApiKey> saveCaptor = ArgumentCaptor.forClass(RagApiKey.class);
            verify(apiKeyRepository).save(saveCaptor.capture());

            RagApiKey saved = saveCaptor.getValue();
            assertThat(saved.getKeyId()).isEqualTo("rag_sk_entity_test");
        }
    }
}
