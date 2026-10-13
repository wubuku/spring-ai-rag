package com.springairag.core.documentprocessing;

import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DocumentProcessingRunServiceTest {

    @Test
    void disabledLedgerDoesNotAdvanceSupersededRuns() {
        DocumentProcessingRunRepository repository =
                mock(DocumentProcessingRunRepository.class);
        RagProperties properties = new RagProperties();
        properties.getDocumentProcessing().setEnabled(false);
        DocumentProcessingRunService service = new DocumentProcessingRunService(
                repository,
                mock(com.springairag.core.embeddingjob.EmbeddingJobRepository.class),
                mock(RagDocumentRepository.class),
                mock(EmbeddingProfileProvider.class),
                new DocumentDerivationDescriptorProvider(properties),
                mock(CollectionRetrievalScopeResolver.class),
                properties);

        assertEquals(0, service.markSuperseded(1L, 2L, 3L));
        verify(repository, never()).markSuperseded(1L, 2L, 3L);
    }
}
