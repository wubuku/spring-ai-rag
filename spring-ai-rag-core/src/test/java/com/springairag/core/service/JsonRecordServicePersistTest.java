package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService persist 路径残余（Batch 384）：ASYNC 策略在派
 * 发缺失时拒绝；无事务管理器时经 persistInTransaction 创建新记录
 * （resolve → validate → persist → SKIP 嵌入）。
 */
class JsonRecordServicePersistTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver resolver;
    private EmbeddingProfileProvider embeddingProfileProvider;
    private DocumentVersionService versionService;
    private RagProperties properties;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        resolver = mock(CollectionIdentityResolver.class);
        embeddingProfileProvider = mock(EmbeddingProfileProvider.class);
        versionService = mock(DocumentVersionService.class);
        properties = new RagProperties();
        lenient().when(versionService.forceRecordVersion(
                any(com.springairag.core.entity.RagDocument.class),
                anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(1);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(
                any(com.springairag.core.entity.RagDocument.class)))
                .thenAnswer(invocation -> {
                    com.springairag.core.entity.RagDocument value =
                            invocation.getArgument(0);
                    value.setId(41L);
                    return value;
                });
        lenient().when(resolver.beginActiveWrite(7L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(7L, 0L));
        lenient().when(embeddingProfileProvider.getActiveProfile())
                .thenReturn(new com.springairag.core.config.EmbeddingProfile(
                        9L, "profile", "test", "model", "v1",
                        1024, "COSINE", "NONE", true));

        service = newService();
    }

    private JsonRecordService newService() {
        return new JsonRecordService(
                documentRepository,
                versionService,
                mock(DocumentEmbedService.class),
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                embeddingProfileProvider,
                resolver,
                properties,
                MAPPER,
                mock(JdbcTemplate.class),
                null);
    }

    private JsonRecordUpsertRequest validRequest() {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(7L);
        request.setExternalId("rec-1");
        request.setTitle("Record");
        request.setRetrievalText("text");
        try {
            request.setJsonbPayload(MAPPER.readTree("{\"k\":1}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return request;
    }

    @Test
    void upsertRejectsAsyncPolicyWhenDispatchMissing() {
        JsonRecordUpsertRequest request = validRequest();
        request.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);

        RagException error = assertThrows(RagException.class,
                () -> service.upsert(request));
        assertEquals(ErrorCode.EMBEDDING_JOBS_DISABLED,
                error.getErrorCodeEnum());
    }

    @Test
    void upsertCreatesNewRecordOnSkipPolicyWithoutTransactionManager() {
        when(documentRepository
                .findByCollectionIdAndDocumentTypeAndExternalId(
                        eq(7L), eq(com.springairag.core.entity.RagDocument.JSON_RECORD),
                        eq("rec-1")))
                .thenReturn(Optional.empty());

        JsonRecordUpsertResponse response =
                service.upsert(validRequest());

        assertEquals("CREATED", response.action());
        assertEquals(41L, response.documentId());
        assertEquals(7L, response.collectionId());
    }
}
