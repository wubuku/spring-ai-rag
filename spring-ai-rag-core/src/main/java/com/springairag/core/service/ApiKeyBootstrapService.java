package com.springairag.core.service;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.repository.RagApiPrincipalRepository;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 启动时审计 legacy 管理入口。服务不再把新生成的 raw ADMIN secret 写入日志。
 */
@Service
public class ApiKeyBootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyBootstrapService.class);

    private final RagApiPrincipalRepository principalRepository;
    private final EnvironmentRootCredentialResolver rootCredentialResolver;

    public ApiKeyBootstrapService(
            RagApiPrincipalRepository principalRepository,
            EnvironmentRootCredentialResolver rootCredentialResolver) {
        this.principalRepository = principalRepository;
        this.rootCredentialResolver = rootCredentialResolver;
    }

    @Override
    @Transactional(readOnly = true)
    public void run(ApplicationArguments args) {
        if (rootCredentialResolver.isConfigured()) {
            log.info("Environment root mode active; legacy ADMIN key bootstrap is disabled");
            return;
        }
        if (principalRepository.countUsableByRole(
                ApiKeyRole.ADMIN, LocalDateTime.now()) == 0) {
            log.error("No usable legacy ADMIN API principal exists; configure RAG_ROOT_API_KEY "
                    + "to recover the management plane");
        }
    }
}
