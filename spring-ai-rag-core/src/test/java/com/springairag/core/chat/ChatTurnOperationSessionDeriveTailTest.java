package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatTurnOperationService 会话派生长尾（Batch 562，JaCoCo 驱动）：
 * withEffectiveSession 快速路径（合法会话 id 原样保留）经 4 参 claim
 * 的 claimNew 分支验证；重新生成分支经 ChatCommand 构造器校验确认
 * 为防御性死代码（非法会话 id 无法进入命令对象）。
 */
class ChatTurnOperationSessionDeriveTailTest {

    private static final String PRINCIPAL_ID = "principal-1";
    private static final String KEY_HASH = "key-hash";

    private ChatTurnOperationRepository repository;
    private ChatModelRouter modelRouter;
    private ChatTurnOperationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        modelRouter = mock(ChatModelRouter.class);
        ChatExecutionService executionService = mock(ChatExecutionService.class);
        when(executionService.resolveCandidateRefs(
                any(ChatCommand.class), anyBoolean()))
                .thenReturn(List.of("vendor/fallback"));
        service = new ChatTurnOperationService(
                repository,
                new ObjectMapper().findAndRegisterModules(),
                new RagChatProperties(),
                new com.springairag.core.config.RagProperties(),
                mock(ChatAuthorizationService.class),
                mock(ChatObservabilityService.class),
                executionService);
    }

    private ChatCommand command(String sessionId) {
        return new ChatCommand(
                "hello", sessionId, ChatPrincipal.local(), null,
                ChatMode.PLAIN, MemoryMode.SERVER, null, null,
                null, null, java.util.Map.of());
    }

    private ChatTurnOperation operation(String sessionId) {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, PRINCIPAL_ID, KEY_HASH, "fp-hash", 1,
                sessionId, UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 0L, 1,
                null, null, null, null, "{}",
                now, now, null);
    }

    private void stubFindThenInsert() {
        // 首次 find（current 查询）→ null；insert 成功后的 find → inserted。
        AtomicInteger finds = new AtomicInteger();
        when(repository.find(anyString(), anyString())).thenAnswer(
                invocation -> finds.incrementAndGet() == 1
                        ? null : operation("session-1"));
        // 4 参 claim 路径的 insert 为 10 参（末尾为授权快照，可为 null）。
        when(repository.insert(
                anyString(), anyString(), anyString(), anyString(),
                any(UUID.class), any(), any(UUID.class), anyInt(),
                anyString(), any()))
                .thenReturn(true);
    }

    @Test
    void validSessionIdIsKeptAsIsBeforeInsert() {
        var fallback = new ChatModelRouter.ChatModelCandidate(
                "vendor/fallback", mock(ChatModel.class),
                new com.springairag.core.config.MultiModelProperties
                        .ModelCapabilities(true, true));
        when(modelRouter.orderedCandidateDescriptors(null))
                .thenReturn(List.of(fallback));
        stubFindThenInsert();

        service.claim(
                new ChatTurnOperationService.Prepared(
                        ChatPrincipal.local(), KEY_HASH, "fp-hash",
                        null, null, true),
                command("session-1"),
                ChatTurnOperation.Transport.NATIVE_JSON,
                false);

        ArgumentCaptor<String> sessionCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(repository).insert(
                anyString(), anyString(), anyString(),
                sessionCaptor.capture(),
                any(UUID.class), any(), any(UUID.class), anyInt(),
                anyString(), any());
        // 合法会话 id 原样保留（withEffectiveSession 快速路径）。
        assertEquals("session-1", sessionCaptor.getValue());
    }

    @Test
    void commandConstructorRejectsInvalidSessionIdBeforeClaim() {
        // ChatCommand 构造器强制校验：非法会话 id 无法进入 4 参 claim，
        // withEffectiveSession 的重新生成分支为防御性死代码。
        assertThrows(IllegalArgumentException.class,
                () -> command("bad session!!"));
    }
}
