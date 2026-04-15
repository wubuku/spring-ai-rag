package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.repository.RagApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Bootstraps the first admin API key if the system has no API keys yet.
 *
 * <p>On first run (empty {@code rag_api_key} table), uses {@link ApiKeyManagementService}
 * to generate a key (with {@code rag_sk_} prefix, same as all other keys), then
 * updates its role to ADMIN.
 *
 * <p>The raw key is printed to startup logs — it is only shown once.
 * Users must copy it from the startup log.
 */
@Service
public class ApiKeyBootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyBootstrapService.class);

    private final ApiKeyManagementService apiKeyManagementService;
    private final RagApiKeyRepository apiKeyRepository;

    public ApiKeyBootstrapService(ApiKeyManagementService apiKeyManagementService,
                                   RagApiKeyRepository apiKeyRepository) {
        this.apiKeyManagementService = apiKeyManagementService;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (apiKeyRepository.count() > 0) {
            // Keys already exist — nothing to bootstrap
            return;
        }

        // Generate a key using the existing service (rag_sk_ prefix, hash stored)
        ApiKeyCreatedResponse admin = apiKeyManagementService.generateKey(
                new ApiKeyCreateRequest("Admin Key (auto-generated)", null));

        // Update the role to ADMIN (generateKey does not set role since the field
        // was added later; this UPDATE runs in the same transaction)
        Optional<RagApiKey> entity = apiKeyRepository.findByKeyId(admin.getKeyId());
        if (entity.isEmpty()) {
            log.error("Bootstrap failed: key not found after generation: {}", admin.getKeyId());
            return;
        }
        RagApiKey key = entity.get();
        key.setRole(ApiKeyRole.ADMIN);
        apiKeyRepository.save(key);

        log.info("");
        log.info("================================================================================");
        log.info("🔑  FIRST-TIME SETUP: Admin API Key Generated");
        log.info("================================================================================");
        log.info("  Public Key ID: {}", admin.getKeyId());
        log.info("  Raw API Key:   {}", admin.getRawKey());
        log.info("");
        log.info("  ⚠️  Save the raw key now — it cannot be retrieved again.");
        log.info("  You will need it to manage API keys via the WebUI or REST API.");
        log.info("================================================================================");
        log.info("");
    }
}
