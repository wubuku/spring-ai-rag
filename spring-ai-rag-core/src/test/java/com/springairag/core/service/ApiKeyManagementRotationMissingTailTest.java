package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.repository.RagApiPrincipalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiKeyManagementService 长尾补充（Batch 710，JaCoCo 驱动）：
 * rotateKey / rotateManagedKey 在凭据引用的 principal 缺失时的
 * NOT_FOUND、响应装配对空白允许集合列表的 null 归一。
 *
 * 勿再投入：1375-1376（SHA-256 不可用）为防御臂；
 * 220-222（provisioning 重试耗尽/中断出口）需重试持续可重试失败
 * 或线程中断时序，复杂度高暂缓。
 */
class ApiKeyManagementRotationMissingTailTest {

    private RagApiKeyRepository apiKeyRepository;
    private RagApiPrincipalRepository principalRepository;
    private ApiKeyManagementService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        apiKeyRepository = mock(RagApiKeyRepository.class);
        principalRepository = mock(RagApiPrincipalRepository.class);
        service = new ApiKeyManagementService(
                apiKeyRepository,
                principalRepository,
                mock(CollectionIdentityResolver.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(com.springairag.core.repository.ApiKeyProvisioningOperationRepository.class),
                mock(com.springairag.core.repository.ApiKeyRotationOperationRepository.class),
                new RagProperties(),
                null,
                mock(ApiPrincipalLifecycleEventPublisher.class));
    }

    private RagApiKey keyReferencing(String principalId) {
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_missing");
        key.setPrincipalId(principalId);
        return key;
    }

    @Test
    void rotateKeyThrowsNotFoundWhenPrincipalMissing() {
        when(apiKeyRepository.findByKeyId("rag_k_missing"))
                .thenReturn(Optional.of(keyReferencing("p-ghost")));
        when(principalRepository.acquireManagementWrite("p-ghost"))
                .thenReturn(1);
        when(principalRepository.findByPrincipalId("p-ghost"))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.rotateKey("rag_k_missing"));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void toResponseNormalizesBlankAllowedCollectionsToNull()
            throws Exception {
        RagApiPrincipal principal = new RagApiPrincipal();
        principal.setPrincipalId("p-1");
        principal.setName("Prod");
        principal.setRole(ApiKeyRole.NORMAL);
        principal.setAllowedCollectionIds("  ");
        RagApiKey credential = new RagApiKey();
        credential.setKeyId("rag_k_1");
        credential.setPrincipalId("p-1");
        when(principalRepository.findByPrincipalId("p-1"))
                .thenReturn(Optional.of(principal));

        Method method = ApiKeyManagementService.class.getDeclaredMethod(
                "toResponse", RagApiKey.class);
        method.setAccessible(true);
        ApiKeyResponse response =
                (ApiKeyResponse) method.invoke(service, credential);

        assertNull(response.getAllowedCollectionIds());
        assertEquals("Prod", response.getName());
    }
}
