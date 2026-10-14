package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChunkingUpgradeServiceTest {

    @Test
    void unavailableRegistrationDoesNotBlockStartupAndLaterScanCanRecover() {
        var jdbc = mock(JdbcTemplate.class);
        var profiles = mock(EmbeddingProfileProvider.class);
        var embeddings = mock(EmbeddingBatchService.class);
        var properties = new RagProperties();
        when(profiles.getActiveProfile()).thenThrow(new IllegalStateException("unavailable"));
        var upgrade = new ChunkingUpgradeService(jdbc, mock(RagDocumentRepository.class), profiles,
                properties, new DocumentDerivationDescriptorProvider(properties),
                mock(DocumentChunkingService.class), mock(KeywordIndexPersistenceService.class),
                mock(EmbeddingPersistenceService.class), embeddings, mock(PlatformTransactionManager.class));
        try {
            assertDoesNotThrow(() -> upgrade.run(null));
            doReturn(new EmbeddingProfile(1, "test", "test", "test", "v1", 1024,
                    "COSINE", "PROVIDER_DEFAULT", true)).when(profiles).getActiveProfile();
            assertDoesNotThrow(upgrade::scanOnce);
            verifyNoInteractions(embeddings);
        } finally {
            upgrade.close();
        }
    }
}
