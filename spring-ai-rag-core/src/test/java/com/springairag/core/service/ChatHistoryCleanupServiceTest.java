package com.springairag.core.service;

import com.springairag.core.config.RagMemoryProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatHistoryCleanupServiceTest {

    @Mock
    private RagChatHistoryRepository chatHistoryRepository;

    @Mock
    private RagMemoryProperties memoryProperties;

    @InjectMocks
    private ChatHistoryCleanupService cleanupService;

    @Test
    void cleanupExpiredChatHistory_ttlDisabled_skipsCleanup() {
        when(memoryProperties.getMessageTtlDays()).thenReturn(0);

        cleanupService.cleanupExpiredChatHistory();

        verify(memoryProperties).getMessageTtlDays();
        verifyNoInteractions(chatHistoryRepository);
    }

    @Test
    void cleanupExpiredChatHistory_ttlNegative_skipsCleanup() {
        when(memoryProperties.getMessageTtlDays()).thenReturn(-1);

        cleanupService.cleanupExpiredChatHistory();

        verify(memoryProperties).getMessageTtlDays();
        verifyNoInteractions(chatHistoryRepository);
    }

    @Test
    void cleanupExpiredChatHistory_ttl30_deletesOldRecords() {
        when(memoryProperties.getMessageTtlDays()).thenReturn(30);
        when(chatHistoryRepository.deleteOlderThan(any(LocalDateTime.class))).thenReturn(5);

        cleanupService.cleanupExpiredChatHistory();

        verify(memoryProperties).getMessageTtlDays();
        verify(chatHistoryRepository).deleteOlderThan(any(LocalDateTime.class));
    }

    @Test
    void cleanupExpiredChatHistory_repositoryException_logged() {
        when(memoryProperties.getMessageTtlDays()).thenReturn(30);
        when(chatHistoryRepository.deleteOlderThan(any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("DB error"));

        // Should not throw
        cleanupService.cleanupExpiredChatHistory();

        verify(memoryProperties).getMessageTtlDays();
        verify(chatHistoryRepository).deleteOlderThan(any(LocalDateTime.class));
    }

    @Test
    void cleanupOlderThan_nullCutoff_returnsZero() {
        int result = cleanupService.cleanupOlderThan(null);
        assertEquals(0, result);
        verifyNoInteractions(chatHistoryRepository);
    }

    @Test
    void cleanupOlderThan_validCutoff_delegatesToRepository() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        when(chatHistoryRepository.deleteOlderThan(cutoff)).thenReturn(3);

        int result = cleanupService.cleanupOlderThan(cutoff);

        assertEquals(3, result);
        verify(chatHistoryRepository).deleteOlderThan(cutoff);
    }
}
