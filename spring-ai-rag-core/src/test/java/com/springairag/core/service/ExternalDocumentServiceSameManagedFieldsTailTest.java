package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ExternalDocumentService.sameManagedFields 真值表（Batch 468）。
 */
class ExternalDocumentServiceSameManagedFieldsTailTest {

    private ExternalDocumentService createService() {
        return new ExternalDocumentService(
                mock(com.springairag.core.repository.RagDocumentRepository.class),
                mock(com.springairag.core.repository.RagCollectionRepository.class),
                mock(com.springairag.core.repository.RagEmbeddingRepository.class),
                mock(com.springairag.core.service.DocumentVersionService.class),
                mock(com.springairag.core.service.DocumentEmbedService.class),
                mock(com.springairag.core.config.EmbeddingProfileProvider.class),
                mock(com.springairag.core.service.CollectionIdentityResolver.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    private boolean sameFields(RagDocument doc, String title, String hash,
                               String source, String type,
                               Map<String, Object> metadata) throws Exception {
        var svc = createService();
        Method method = ExternalDocumentService.class.getDeclaredMethod(
                "sameManagedFields", RagDocument.class,
                String.class, String.class, String.class,
                String.class, Map.class);
        method.setAccessible(true);
        return (boolean) method.invoke(svc, doc, title, hash, source,
                type, metadata);
    }

    private RagDocument managedDoc() {
        RagDocument doc = new RagDocument();
        doc.setTitle("T");
        doc.setContentHash(com.springairag.core.util.DigestUtils.sha256("c"));
        doc.setSource("cms");
        doc.setDocumentType("text");
        doc.setMetadata(Map.of("k", "v"));
        doc.setEnabled(Boolean.TRUE);
        return doc;
    }

    @Test
    void matchingFieldsReturnTrue() throws Exception {
        RagDocument doc = managedDoc();
        assertTrue((Boolean) sameFields(doc, "T",
                com.springairag.core.util.DigestUtils.sha256("c"), "cms",
                "text", Map.of("k", "v")));
    }

    @Test
    void titleDivergenceReturnsFalse() throws Exception {
        RagDocument doc = managedDoc();
        doc.setTitle("Other");
        assertFalse((Boolean) sameFields(doc, "T",
                doc.getContentHash(), "cms", "text", Map.of("k", "v")));
    }

    @Test
    void hashDivergenceReturnsFalse() throws Exception {
        RagDocument doc = managedDoc();
        doc.setContentHash("old-hash");
        assertFalse((Boolean) sameFields(doc, "T",
                "new-hash", "cms", "text", Map.of("k", "v")));
    }

    @Test
    void sourceDivergenceReturnsFalse() throws Exception {
        RagDocument doc = managedDoc();
        doc.setSource("other-source");
        assertFalse((Boolean) sameFields(doc, "T",
                doc.getContentHash(), "cms", "text", Map.of("k", "v")));
    }

    @Test
    void typeDivergenceReturnsFalse() throws Exception {
        RagDocument doc = managedDoc();
        doc.setDocumentType("markdown");
        assertFalse((Boolean) sameFields(doc, "T",
                doc.getContentHash(), "cms", "text", Map.of("k", "v")));
    }

    @Test
    void metadataDivergenceReturnsFalse() throws Exception {
        RagDocument doc = managedDoc();
        doc.setMetadata(Map.of("k", "other"));
        assertFalse((Boolean) sameFields(doc, "T",
                doc.getContentHash(), "cms", "text", Map.of("k", "v")));
    }
}
