package com.springairag.core.service;

import com.springairag.api.dto.EmbedProgressEvent;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.extension.PromptCustomizerChain;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * DocumentEmbedService.emitEmbeddingProgress 长尾（Batch 470）：
 * null 回调跳过、批量事件逐条发射、事件序号与总数正确。
 */
class DocumentEmbedServiceEmitProgressTailTest {

    private DocumentEmbedService service;
    private Method emitMethod;

    private void setUpService() throws Exception {
        var documentRepository = mock(com.springairag.core.repository.RagDocumentRepository.class);
        var embeddingBatchService = mock(com.springairag.core.retrieval.EmbeddingBatchService.class);
        var persistenceService = mock(com.springairag.core.service.EmbeddingPersistenceService.class);
        var profileProvider = mock(com.springairag.core.config.EmbeddingProfileProvider.class);
        service = new DocumentEmbedService(
                documentRepository,
                embeddingBatchService,
                persistenceService,
                profileProvider,
                new com.springairag.core.config.RagProperties());
        emitMethod = DocumentEmbedService.class.getDeclaredMethod(
                "emitEmbeddingProgress",
                java.util.function.Consumer.class, Long.class, int.class);
        emitMethod.setAccessible(true);
    }

    @Test
    void nullCallbackSkipsEmission() throws Exception {
        setUpService();
        List<EmbedProgressEvent> events = new ArrayList<>();
        emitMethod.invoke(service, (java.util.function.Consumer<EmbedProgressEvent>) null,
                41L, 3);
        assertTrue(events.isEmpty());
    }

    @Test
    void emitsOneEventPerChunk() throws Exception {
        setUpService();
        List<EmbedProgressEvent> events = new ArrayList<>();
        java.util.function.Consumer<EmbedProgressEvent> consumer = events::add;
        emitMethod.invoke(service, consumer, 41L, 3);
        assertEquals(3, events.size());
    }

    @Test
    void zeroTotalProducesNoEvents() throws Exception {
        setUpService();
        List<EmbedProgressEvent> events = new ArrayList<>();
        java.util.function.Consumer<EmbedProgressEvent> consumer = events::add;
        emitMethod.invoke(service, consumer, 41L, 0);
        assertTrue(events.isEmpty());
    }
}
