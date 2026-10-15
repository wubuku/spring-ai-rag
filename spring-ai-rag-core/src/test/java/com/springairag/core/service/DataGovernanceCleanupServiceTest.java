package com.springairag.core.service;

import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagAuditLogRepository;
import com.springairag.core.repository.RagClientErrorRepository;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataGovernanceCleanupServiceTest {

    @Mock
    private RagAuditLogRepository auditLogRepository;
    @Mock
    private RagClientErrorRepository clientErrorRepository;
    @Mock
    private RagRetrievalEvaluationRepository evaluationRepository;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private RagProperties properties;
    private DataGovernanceCleanupService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getDataGovernance().setCleanupBatchSize(100);
        properties.getDataGovernance().setCleanupMaxBatches(2);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        service = new DataGovernanceCleanupService(
                properties, auditLogRepository, clientErrorRepository,
                evaluationRepository, transactionManager);
    }

    @Test
    void cleanupExpiredData_respectsPerCategoryBatchAndBatchCountBounds() {
        when(auditLogRepository.deleteExpiredBatch(any(), eq(100))).thenReturn(100);
        when(clientErrorRepository.deleteExpiredBatch(any(), eq(100))).thenReturn(100);
        when(evaluationRepository.deleteExpiredBatch(any(), eq(100))).thenReturn(100);

        service.cleanupExpiredData();

        verify(auditLogRepository, times(2)).deleteExpiredBatch(any(), eq(100));
        verify(clientErrorRepository, times(2)).deleteExpiredBatch(any(), eq(100));
        verify(evaluationRepository, times(2)).deleteExpiredBatch(any(), eq(100));
        verify(transactionManager, times(6)).getTransaction(any());
        verify(transactionManager, times(6)).commit(transactionStatus);
    }

    @Test
    void cleanupExpiredData_isolatesCategoryFailureAndContinues() {
        when(auditLogRepository.deleteExpiredBatch(any(), eq(100)))
                .thenThrow(new IllegalStateException("audit unavailable"));
        when(clientErrorRepository.deleteExpiredBatch(any(), eq(100))).thenReturn(0);
        when(evaluationRepository.deleteExpiredBatch(any(), eq(100))).thenReturn(0);

        service.cleanupExpiredData();

        verify(clientErrorRepository).deleteExpiredBatch(any(), eq(100));
        verify(evaluationRepository).deleteExpiredBatch(any(), eq(100));
        verify(transactionManager, times(3)).getTransaction(any());
    }

    @Test
    void cleanupExpiredData_skipsWhenDisabled() {
        properties.getDataGovernance().setCleanupEnabled(false);

        service.cleanupExpiredData();

        verifyNoInteractions(auditLogRepository, clientErrorRepository, evaluationRepository,
                transactionManager);
    }
}
