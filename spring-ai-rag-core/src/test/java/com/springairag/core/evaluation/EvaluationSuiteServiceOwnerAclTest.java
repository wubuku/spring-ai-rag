package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.springairag.api.dto.EvaluationRunCreateRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagApiKeyRepository;
import com.springairag.core.retrieval.RetrievalFilterValidator;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.RetrievalEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationSuiteServiceOwnerAclTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private EvaluationSuiteRepository repository;
    private CollectionRetrievalScopeResolver scopeResolver;
    private RagApiKeyRepository apiKeyRepository;
    private EvaluationCaseExecutor caseExecutor;
    private EmbeddingProfileProvider profileProvider;
    private RagProperties properties;
    private EvaluationSuiteService service;

    @BeforeEach
    void setUp() {
        repository = mock(EvaluationSuiteRepository.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        apiKeyRepository = mock(RagApiKeyRepository.class);
        caseExecutor = mock(EvaluationCaseExecutor.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        properties = new RagProperties();
        EvaluationSuiteDefinitionValidator validator =
                new EvaluationSuiteDefinitionValidator(
                        mapper, new RetrievalFilterValidator(), properties);
        service = new EvaluationSuiteService(
                repository,
                validator,
                scopeResolver,
                caseExecutor,
                mock(RetrievalEvaluationService.class),
                profileProvider,
                mapper,
                properties,
                apiKeyRepository);
    }

    @Test
    void localPrincipalDoesNotLoadADatabaseApiKey() {
        assertNull(service.resolveExecutionKey("local:auth-disabled"));
        assertNull(service.resolveExecutionKey("root:environment-root"));
        assertNull(service.resolveExecutionKey("legacy:static"));
        verify(apiKeyRepository, never()).findByKeyId(any());
    }

    @Test
    void missingOwnerKeyFailsTheRunWithoutSearching() {
        UUID runId = UUID.randomUUID();
        String workerId = "worker-a";
        when(apiKeyRepository.findByKeyId("rag_k_gone")).thenReturn(Optional.empty());

        service.executeRun(
                run(runId, UUID.randomUUID(), "db:rag_k_gone"), workerId);

        verify(repository).finishRun(
                runId, workerId, "FAILED", "{}", "AUTHORIZATION_CHANGED");
        verify(repository, never()).findVersionById(any());
        verify(repository, never()).insertCaseResult(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        verify(scopeResolver, never()).resolve(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void revokedCollectionAccessFailsTheRunWithoutSearching() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String workerId = "worker-a";
        RagApiKey ownerKey = new RagApiKey();
        ownerKey.setKeyId("rag_k_owner");
        ownerKey.setEnabled(true);
        ownerKey.setAllowedCollectionIds("999");
        when(apiKeyRepository.findByKeyId("rag_k_owner"))
                .thenReturn(Optional.of(ownerKey));
        when(repository.findVersionById(versionId)).thenReturn(Optional.of(
                new EvaluationSuiteRepository.VersionRow(
                        versionId,
                        UUID.randomUUID(),
                        1,
                        definition(),
                        "sha",
                        OffsetDateTime.now())));
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), eq(ownerKey)))
                .thenThrow(new SecurityException("Collection is not authorized"));

        service.executeRun(run(runId, versionId, "db:rag_k_owner"), workerId);

        verify(repository).finishRun(
                runId, workerId, "FAILED", "{}", "AUTHORIZATION_CHANGED");
        verify(repository, never()).insertCaseResult(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void disabledOwnerKeyFailsClosed() {
        RagApiKey disabled = new RagApiKey();
        disabled.setKeyId("rag_k_disabled");
        disabled.setEnabled(false);
        when(apiKeyRepository.findByKeyId("rag_k_disabled"))
                .thenReturn(Optional.of(disabled));

        assertThrows(SecurityException.class,
                () -> service.resolveExecutionKey("db:rag_k_disabled"));
    }

    @Test
    void getSuiteLooksUpTheCurrentPrincipalAndHidesOtherOwners() {
        properties.getEvaluation().setManagedSuitesEnabled(true);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(repository.findSuite("local:auth-disabled", "furniture-quality"))
                .thenReturn(Optional.of(new EvaluationSuiteRepository.SuiteRow(
                        id, "furniture-quality", "Furniture",
                        "local:auth-disabled", now)));

        var found = service.getSuite("furniture-quality");
        assertEquals(id, found.id());
        assertEquals("furniture-quality", found.suiteKey());
        verify(repository).findSuite("local:auth-disabled", "furniture-quality");

        when(repository.findSuite("local:auth-disabled", "secret-suite"))
                .thenReturn(Optional.empty());
        RagException missing = assertThrows(
                RagException.class, () -> service.getSuite("secret-suite"));
        assertEquals(ErrorCode.NOT_FOUND, missing.getErrorCodeEnum());
        verify(repository).findSuite("local:auth-disabled", "secret-suite");
    }

    @Test
    void createRunUsesOwnerSlotWhenAllActiveSlotsAreOccupied() throws Exception {
        properties.getEvaluation().setManagedSuitesEnabled(true);
        UUID suiteId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(repository.findSuite("local:auth-disabled", "furniture-quality"))
                .thenReturn(Optional.of(new EvaluationSuiteRepository.SuiteRow(
                        suiteId, "furniture-quality", "Furniture",
                        "local:auth-disabled", OffsetDateTime.now())));
        when(repository.findVersion(suiteId, null))
                .thenReturn(Optional.of(new EvaluationSuiteRepository.VersionRow(
                        versionId, suiteId, 1, definition(), "a".repeat(64),
                        OffsetDateTime.now())));
        when(scopeResolver.resolve(any(), any(), any(), any(), any(), eq(null)))
                .thenReturn(com.springairag.core.retrieval.RetrievalScope.noMatches());
        when(caseExecutor.collectionSnapshot(any())).thenReturn(Map.of());
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                9L, "test", "test", "test", "v1",
                1024, "COSINE", "NONE", true));
        when(repository.tryInsertRun(
                any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Optional.empty());

        RagException limitReached = assertThrows(
                RagException.class,
                () -> service.createRun(new EvaluationRunCreateRequest(
                        "furniture-quality", null, null)));

        assertEquals(
                ErrorCode.CONCURRENT_EVALUATION_LIMIT,
                limitReached.getErrorCodeEnum());
        verify(repository).tryInsertRun(
                any(), any(), any(), any(), any(), any(), anyInt());
    }

    private EvaluationSuiteRepository.RunRow run(
            UUID runId, UUID versionId, String owner) {
        ObjectNode configuration = mapper.createObjectNode();
        configuration.set("collectionSnapshot", mapper.createObjectNode());
        configuration.putArray("variantKeys").add("default");
        OffsetDateTime now = OffsetDateTime.now();
        return new EvaluationSuiteRepository.RunRow(
                runId, versionId, owner, "RUNNING",
                configuration, "unknown", "test",
                mapper.nullNode(), null, now, null, now);
    }

    private ObjectNode definition() throws Exception {
        return mapper.readValue("""
                {
                  "cases": [{
                    "id": "exact-sofa",
                    "query": "破皮沙发",
                    "scope": {
                      "mode": "SELECTED_COLLECTIONS",
                      "collectionKeys": ["furniture"]
                    },
                    "relevant": [{
                      "collectionKey": "furniture",
                      "externalId": "sofa-001"
                    }]
                  }]
                }
                """, ObjectNode.class);
    }
}
