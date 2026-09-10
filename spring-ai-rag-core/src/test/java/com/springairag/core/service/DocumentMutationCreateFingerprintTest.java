package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.DocumentDeduplicationScope;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * localCreateFingerprint 幂等指纹规范化：标题修剪、元数据键序
 * 无关（TreeMap 排序）、enabledOverride/originalFilename/jsonbPayload
 * /policy/force/collectionId/deduplicationScope 任一变化均改变指纹。
 */
class DocumentMutationCreateFingerprintTest {

    private DocumentMutationService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        RagDocumentRepository documentRepository =
                mock(RagDocumentRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        CollectionIdentityResolver resolver =
                mock(CollectionIdentityResolver.class);
        DocumentVersionService versionService = mock(DocumentVersionService.class);
        EmbeddingDispatchService dispatchService =
                mock(EmbeddingDispatchService.class);
        DocumentEmbedService documentEmbedService =
                mock(DocumentEmbedService.class);
        DocumentLifecycleService lifecycleService =
                mock(DocumentLifecycleService.class);
        PlatformTransactionManager transactionManager =
                mock(JpaTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        service = new DocumentMutationService(
                documentRepository,
                embeddingRepository,
                resolver,
                versionService,
                dispatchService,
                documentEmbedService,
                lifecycleService,
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                (objectMapper = new ObjectMapper()),
                new RagProperties(),
                transactionManager);
    }

    private DocumentRequest request() {
        DocumentRequest request = new DocumentRequest();
        request.setTitle("  Padded Title  ");
        request.setContent("body text");
        request.setSource("  ");
        request.setDocumentType("text");
        request.setMetadata(null);
        request.setDeduplicationScope(DocumentDeduplicationScope.COLLECTION);
        return request;
    }

    private String fingerprint(
            DocumentRequest request, Long collectionId,
            EmbeddingPolicy policy, boolean force, String originalFilename,
            com.fasterxml.jackson.databind.JsonNode payload,
            Boolean enabledOverride) {
        String value = ReflectionTestUtils.invokeMethod(service,
                "localCreateFingerprint", request, collectionId, policy,
                force, originalFilename, payload, enabledOverride);
        org.junit.jupiter.api.Assertions.assertNotNull(value);
        return value;
    }

    @Test
    void fingerprintIsDeterministicAndTrimCanonicalizesTitle() throws Exception {
        DocumentRequest padded = request();
        DocumentRequest trimmed = request();
        trimmed.setTitle("Padded Title");

        String first = fingerprint(padded, 10L, EmbeddingPolicy.SKIP,
                false, null, null, null);
        String second = fingerprint(trimmed, 10L, EmbeddingPolicy.SKIP,
                false, null, null, null);

        assertEquals(first, second);
        // 指纹为 64 位十六进制 SHA-256。
        assertEquals(64, first.length());
    }

    @Test
    void metadataKeyOrderDoesNotAffectFingerprint() throws Exception {
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("alpha", 1);
        forward.put("beta", "two");
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("beta", "two");
        reversed.put("alpha", 1);

        DocumentRequest forwardRequest = request();
        forwardRequest.setMetadata(forward);
        DocumentRequest reversedRequest = request();
        reversedRequest.setMetadata(reversed);

        assertEquals(
                fingerprint(forwardRequest, 10L, EmbeddingPolicy.SKIP,
                        false, null, null, null),
                fingerprint(reversedRequest, 10L, EmbeddingPolicy.SKIP,
                        false, null, null, null));
    }

    @Test
    void optionalInputsChangeFingerprint() throws Exception {
        String base = fingerprint(request(), 10L, EmbeddingPolicy.SKIP,
                false, null, null, null);

        // enabledOverride / originalFilename / jsonbPayload 逐一变化。
        assertNotEquals(base, fingerprint(request(), 10L,
                EmbeddingPolicy.SKIP, false, null, null, Boolean.TRUE));
        assertNotEquals(base, fingerprint(request(), 10L,
                EmbeddingPolicy.SKIP, false, "file.pdf", null, null));
        assertNotEquals(base, fingerprint(request(), 10L,
                EmbeddingPolicy.SKIP, false, null,
                objectMapper.readTree("{\"k\":1}"), null));
    }

    @Test
    void policyForceCollectionIdAndScopeChangeFingerprint() {
        String base = fingerprint(request(), 10L, EmbeddingPolicy.SKIP,
                false, null, null, null);

        assertNotEquals(base, fingerprint(request(), 10L,
                EmbeddingPolicy.SKIP, true, null, null, null));
        assertNotEquals(base, fingerprint(request(), 10L,
                EmbeddingPolicy.SYNC, false, null, null, null));
        assertNotEquals(base, fingerprint(request(), 11L,
                EmbeddingPolicy.SKIP, false, null, null, null));

        DocumentRequest otherScope = request();
        otherScope.setDeduplicationScope(DocumentDeduplicationScope.NONE);
        assertNotEquals(base, fingerprint(otherScope, 10L,
                EmbeddingPolicy.SKIP, false, null, null, null));
    }
}
