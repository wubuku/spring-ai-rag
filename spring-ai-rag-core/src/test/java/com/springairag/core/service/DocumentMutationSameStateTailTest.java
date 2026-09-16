package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * DocumentMutationService 外部状态比较真值表（Batch 463）：
 * sameExternalState 要求启用且无删除标记，managed 变体忽略启用
 * 与删除标记；title/contentHash/source/documentType/metadata/
 * payload 六元组逐一参与比较。
 */
class DocumentMutationSameStateTailTest {

    private DocumentMutationService service;
    private Method sameExternalState;
    private Method sameExternalManagedState;

    @BeforeEach
    void setUp() throws Exception {
        service = new DocumentMutationService(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(com.springairag.core.service.CollectionIdentityResolver.class),
                mock(DocumentVersionService.class),
                mock(com.springairag.core.embeddingjob.EmbeddingDispatchService.class),
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                mock(JdbcTemplate.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.springairag.core.config.RagProperties(),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        sameExternalState = DocumentMutationService.class.getDeclaredMethod(
                "sameExternalState", RagDocument.class, String.class,
                String.class, String.class, String.class,
                Map.class, com.fasterxml.jackson.databind.JsonNode.class);
        sameExternalState.setAccessible(true);
        sameExternalManagedState = DocumentMutationService.class
                .getDeclaredMethod("sameExternalManagedState", RagDocument.class,
                        String.class, String.class, String.class, String.class,
                        Map.class, com.fasterxml.jackson.databind.JsonNode.class);
        sameExternalManagedState.setAccessible(true);
    }

    private RagDocument managedDocument() {
        RagDocument doc = new RagDocument();
        doc.setTitle("T");
        doc.setContentHash(com.springairag.core.util.DigestUtils.sha256("c"));
        doc.setSource("cms");
        doc.setDocumentType("text");
        doc.setMetadata(Map.of("k", "v"));
        doc.setJsonbPayload(null);
        doc.setEnabled(Boolean.TRUE);
        return doc;
    }

    private Object sameExternalState(RagDocument doc, String title,
                                     String hash, String source, String type,
                                     Map<String, Object> metadata) throws Exception {
        return sameExternalState.invoke(service, doc, title, hash, source,
                type, metadata, null);
    }

    private Object sameExternalManagedState(RagDocument doc, String title,
                                            String hash, String source, String type,
                                            Map<String, Object> metadata) throws Exception {
        return sameExternalManagedState.invoke(service, doc, title, hash,
                source, type, metadata, null);
    }

    @Test
    void enabledCleanDocumentMatchesItsOwnState() throws Exception {
        RagDocument doc = managedDocument();
        doc.setContent(com.springairag.core.util.DigestUtils.sha256("c"));

        assertTrue((Boolean) sameExternalState(doc, "T",
                com.springairag.core.util.DigestUtils.sha256("c"), "cms",
                "text", Map.of("k", "v")));
        assertTrue((Boolean) sameExternalManagedState(doc, "T",
                com.springairag.core.util.DigestUtils.sha256("c"), "cms",
                "text", Map.of("k", "v")));
    }

    @Test
    void disabledOrTombstonedDocumentNeverMatchesExternalState()
            throws Exception {
        RagDocument disabled = managedDocument();
        disabled.setEnabled(Boolean.FALSE);
        assertFalse((Boolean) sameExternalState(disabled, "T",
                com.springairag.core.util.DigestUtils.sha256("c"), "cms",
                "text", Map.of("k", "v")));

        RagDocument tombstoned = managedDocument();
        tombstoned.setSourceDeletedAt(java.time.LocalDateTime.now());
        assertFalse((Boolean) sameExternalState(tombstoned, "T",
                com.springairag.core.util.DigestUtils.sha256("c"), "cms",
                "text", Map.of("k", "v")));
    }

    @Test
    void managedComparisonIgnoresEnabledAndDeletionMarkers() throws Exception {
        RagDocument tombstoned = managedDocument();
        tombstoned.setEnabled(Boolean.FALSE);
        tombstoned.setSourceDeletedAt(java.time.LocalDateTime.now());
        tombstoned.setTitle("T");

        // managed 比较不看 enabled/deleted，仅六元组字段一致即匹配。
        assertTrue((Boolean) sameExternalManagedState(tombstoned, "T",
                com.springairag.core.util.DigestUtils.sha256("c"), "cms",
                "text", Map.of("k", "v")));
    }

    @Test
    void anyFieldDivergenceBreaksExternalStateMatch() throws Exception {
        RagDocument base = managedDocument();
        base.setTitle("Other");
        assertFalse((Boolean) sameExternalState(base, "T",
                com.springairag.core.util.DigestUtils.sha256("c"), "cms",
                "text", Map.of("k", "v")));

        RagDocument hashDrift = managedDocument();
        hashDrift.setContentHash("drift");
        assertFalse((Boolean) sameExternalState(hashDrift, "T",
                com.springairag.core.util.DigestUtils.sha256("c"), "cms",
                "text", Map.of("k", "v")));

        RagDocument sourceDrift = managedDocument();
        sourceDrift.setSource("other");
        assertFalse((Boolean) sameExternalState(sourceDrift, "T",
                com.springairag.core.util.DigestUtils.sha256("c"), "cms",
                "text", Map.of("k", "v")));

        RagDocument typeDrift = managedDocument();
        typeDrift.setDocumentType("markdown");
        assertFalse((Boolean) sameExternalState(typeDrift, "T",
                com.springairag.core.util.DigestUtils.sha256("c"), "cms",
                "text", Map.of("k", "v")));

        RagDocument metadataDrift = managedDocument();
        metadataDrift.setMetadata(Map.of("k", "other"));
        assertFalse((Boolean) sameExternalState(metadataDrift, "T",
                com.springairag.core.util.DigestUtils.sha256("c"), "cms",
                "text", Map.of("k", "v")));
    }
}
