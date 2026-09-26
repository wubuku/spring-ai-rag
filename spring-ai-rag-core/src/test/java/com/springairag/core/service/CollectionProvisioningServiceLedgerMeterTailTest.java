package com.springairag.core.service;

import com.springairag.api.dto.CollectionRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.CollectionProvisioningOperation;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.CollectionProvisioningOperationRepository;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CollectionProvisioningService 台账降级与指标长尾（Batch 664，
 * JaCoCo 驱动）：重试耗尽后 readExisting 保留数据访问根因包装
 * unavailable、幂等开关关闭短路且计数 disabled、无指标注册表时
 * 记录不失败。
 */
class CollectionProvisioningServiceLedgerMeterTailTest {

    private static final String OWNER = "db:principal-1";
    private static final String KEY_HASH = "a".repeat(64);

    private CollectionProvisioningOperationRepository operationRepository;
    private MeterRegistry meterRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        operationRepository = mock(CollectionProvisioningOperationRepository.class);
        meterRegistry = new SimpleMeterRegistry();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<MeterRegistry> meterProvider(
            MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    private CollectionProvisioningService service(
            PlatformTransactionManager transactionManager,
            MeterRegistry registry,
            boolean enabled) {
        RagProperties properties = new RagProperties();
        properties.getCollectionProvisioning().setEnabled(enabled);
        properties.getCollectionProvisioning().setConcurrentRetryAttempts(1);
        return new CollectionProvisioningService(
                operationRepository,
                mock(RagCollectionRepository.class),
                mock(RagDocumentRepository.class),
                mock(RagCollectionService.class),
                properties,
                transactionManager,
                meterProvider(registry));
    }

    private CollectionRequest request() {
        CollectionRequest request = new CollectionRequest();
        request.setCollectionKey("kb");
        request.setName("Knowledge Base");
        return request;
    }

    @Test
    void retryExhaustionWrapsUnavailableWithRootCause() {
        when(operationRepository.findByOwnerIdAndIdempotencyKeyHash(
                OWNER, KEY_HASH))
                .thenThrow(new DataAccessResourceFailureException("race"));

        RagException error = assertThrows(RagException.class,
                () -> service(null, meterRegistry, true)
                        .createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
        assertTrue(error.getCause() instanceof DataAccessResourceFailureException);
        assertEquals(1.0, meterRegistry.counter(
                "rag.collection.provisioning.requests",
                "outcome", "unavailable").count());
    }

    @Test
    void disabledIdempotencyShortCircuitsWithDisabledOutcome() {
        RagException error = assertThrows(RagException.class,
                () -> service(null, meterRegistry, false)
                        .createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(
                ErrorCode.COLLECTION_PROVISIONING_IDEMPOTENCY_DISABLED,
                error.getErrorCodeEnum());
        assertEquals(1.0, meterRegistry.counter(
                "rag.collection.provisioning.requests",
                "outcome", "disabled").count());
    }

    @Test
    void absentMeterRegistryDoesNotBreakRecording() {
        RagException error = assertThrows(RagException.class,
                () -> service(null, null, false)
                        .createOrReplay(request(), OWNER, KEY_HASH));

        assertEquals(
                ErrorCode.COLLECTION_PROVISIONING_IDEMPOTENCY_DISABLED,
                error.getErrorCodeEnum());
        assertEquals(0.0, meterRegistry.counter(
                "rag.collection.provisioning.requests",
                "outcome", "disabled").count());
    }
}
