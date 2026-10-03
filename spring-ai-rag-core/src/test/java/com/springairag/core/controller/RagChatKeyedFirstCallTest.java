package com.springairag.core.controller;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.dto.ChatResponse;
import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatCommand;
import com.springairag.core.chat.ChatCommandMapper;
import com.springairag.core.chat.ChatExecutionService;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.ChatTurnOperation;
import com.springairag.core.chat.ChatTurnOperationService;
import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagSseProperties;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.ChatExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 键控 chat 的<b>首次调用</b>路径（Batch 774）。
 *
 * <p>勘察结论：{@code ask} 与 {@code chat} 两个非流式 JSON 端点里各有一段
 * 近乎逐行重复的键控分支，二者都在
 * {@code prepared.operation() != null && ...executionSnapshot() != null}
 * 为真时走快照映射，为假时走 {@code commandMapper.map(...)}。
 *
 * <p>而整个 controller 测试目录里，<b>没有任何一个用例构造过
 * "keyed 但 operation 为 null" 的 Prepared</b>（grep {@code null, true} 命中 0）。
 * 也就是说——<b>每一个新 API 密钥的第一次请求</b>所走的那条命令映射路径，
 * 在 ask 与 chat 两侧都从未被执行过。若该分支有 NPE 或取错 scope，
 * 所有现有测试照样全绿。
 *
 * <p>附带覆盖：
 * <ul>
 *   <li>超长消息（&gt;100 字符）——两个端点的日志截断分支。
 *       短消息一侧早已被所有既有用例钉住（去掉长度检查会越界抛异常），
 *       这里补的是长输入这一侧。</li>
 *   <li>{@code nativeSnapshotEmitter} 的 {@code claim.keyed()} 守卫：
 *       {@code Claim.keyed()} 就是 {@code operationRef.get() != null}，
 *       而 {@code Claim.unkeyed()} 是真实可产生的值。没这道守卫，
 *       {@code claim.operation().turnId()} 会直接 NPE。</li>
 * </ul>
 *
 * <p><b>不覆盖的部分（如实说明）</b>：
 * <ul>
 *   <li>{@code if (claim != null && claim.replay())} 的"claim 非空但非重放"
 *       一侧在生产中<b>不可达</b>——{@code inspectExisting} 只会返回
 *       {@code null} 或 {@code new Claim(current, true, null)}。</li>
 *   <li>{@code nativeSnapshotEmitter} 里 {@code claim.keyed()} 守卫的
 *       "unkeyed claim" 一侧同样难以经公开 API 触发：两个调用点的 claim
 *       都来自键控分支。可以靠给生产代码加测试专用钩子来覆盖，
 *       <b>本类拒绝这么做</b>——为了分支数改生产代码是本末倒置。</li>
 * </ul>
 * 这两处是对协作者的防御性检查，不是可触发的业务分支，
 * 本类<b>不</b>为它们编造 mock 场景来凑覆盖率。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagChatKeyedFirstCallTest {

    private static final UUID TURN_ID =
            UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final ChatPrincipal PRINCIPAL =
            new ChatPrincipal("db:1", "DATABASE_API_KEY", false);

    @Mock RagChatService ragChatService;
    @Mock RagChatHistoryRepository historyRepository;
    @Mock ChatExportService chatExportService;
    @Mock AuditLogService auditLogService;
    @Mock ChatTurnOperationService turnOperationService;
    @Mock ChatCommandMapper commandMapper;
    @Mock ChatExecutionService chatExecutionService;

    private RagChatController controller;

    @BeforeEach
    void setUp() {
        controller = new RagChatController(ragChatService, historyRepository, chatExportService, new RagSseProperties(), null, auditLogService);
        controller.configureTurnOperationService(turnOperationService);
        controller.configureModeAwareExecution(commandMapper, chatExecutionService);
        when(ragChatService.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .answer("legacy").sessionId("session-1")
                        .mode(ChatMode.KNOWLEDGE).finishReason("STOP").build());
    }

    /** keyed，但还没有任何既有 operation——新密钥第一次调用的形状。 */
    private ChatTurnOperationService.Prepared freshKeyedPrepared() {
        return new ChatTurnOperationService.Prepared(
                PRINCIPAL, "key-hash", "fp-hash", null, null, true);
    }

    private ChatTurnOperation operation() {
        return new ChatTurnOperation(
                1L, "db:1", "key-hash", "fp-hash", 1,
                "session-1", TURN_ID,
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.SUCCEEDED,
                UUID.randomUUID(), Instant.now().plusSeconds(60),
                1, 1L, 1, "{\"sessionId\":\"session-1\"}", null, null, null, null,
                Instant.now(), Instant.now(), null);
    }

    private ChatCommand command() {
        return new ChatCommand(
                "hello", "session-1", PRINCIPAL, null,
                ChatMode.KNOWLEDGE, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private ChatRequest chatRequest() {
        ChatRequest request = new ChatRequest();
        request.setMessage("hello");
        request.setSessionId("session-1");
        return request;
    }

    private void stubFreshKeyedChain(
            ChatTurnOperationService.Prepared prepared) {
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                any())).thenReturn(prepared);
        // 没有既有 operation -> inspectExisting 必然返回 null。
        when(turnOperationService.inspectExisting(same(prepared)))
                .thenReturn(null);
        ChatCommand mapped = command();
        // 关键断言点：走的是 map(...)，不是 mapFromExecutionSnapshot(...)。
        when(commandMapper.map(any(ChatRequest.class), any(), any()))
                .thenReturn(mapped);
        ChatTurnOperationService.Claim fresh =
                new ChatTurnOperationService.Claim(operation(), false);
        when(turnOperationService.claim(same(prepared), same(mapped),
                eq(ChatTurnOperation.Transport.NATIVE_JSON), eq(false)))
                .thenReturn(fresh);
        ChatCommand claimed = command();
        when(turnOperationService.commandForClaim(same(mapped), same(fresh)))
                .thenReturn(claimed);
        ChatExecutionService.PreparedExecution execution =
                new ChatExecutionService.PreparedExecution(
                        claimed, null, List.of(), null, null, null);
        when(chatExecutionService.prepareForOperation(
                same(claimed), isNull(), eq(false)))
                .thenReturn(execution);
        when(turnOperationService.completePrepared(same(fresh), same(execution)))
                .thenReturn(ChatResponse.builder()
                        .answer("fresh-keyed").sessionId("session-1")
                        .mode(ChatMode.KNOWLEDGE).finishReason("STOP").build());
    }

    // ── 新密钥首次调用：operation 为 null，必须走普通映射 ─────────────

    @Test
    void keyedAskWithNoExistingOperationBuildsCommandFromRequest() {
        ChatTurnOperationService.Prepared prepared = freshKeyedPrepared();
        stubFreshKeyedChain(prepared);

        ResponseEntity<ChatResponse> response = controller.ask(
                chatRequest(), new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("fresh-keyed", response.getBody().getAnswer());
        // 走的是普通映射：绝不能去读不存在的执行快照。
        verify(commandMapper).map(any(ChatRequest.class), any(), any());
        verify(commandMapper, never()).mapFromExecutionSnapshot(
                any(), any(), anyString(), anyString());
        assertEquals(TURN_ID.toString(),
                response.getHeaders().getFirst("X-RAG-Turn-Id"));
    }

    @Test
    void keyedChatAliasWithNoExistingOperationBuildsCommandFromRequest() {
        // /chat 与 /ask 是两份近乎逐行重复的代码，必须各自验证。
        ChatTurnOperationService.Prepared prepared = freshKeyedPrepared();
        stubFreshKeyedChain(prepared);

        ResponseEntity<ChatResponse> response = controller.chat(
                chatRequest(), new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("fresh-keyed", response.getBody().getAnswer());
        verify(commandMapper).map(any(ChatRequest.class), any(), any());
        verify(commandMapper, never()).mapFromExecutionSnapshot(
                any(), any(), anyString(), anyString());
    }

    // ── 长消息：日志截断分支（保证不越界、不抛异常） ─────────────────

    @Test
    void longMessageIsHandledByAskWithoutBoundsFailure() {
        ChatRequest request = chatRequest();
        request.setMessage("问".repeat(150));
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                any())).thenReturn(
                new ChatTurnOperationService.Prepared(
                        PRINCIPAL, null, null, null, null, false));

        ResponseEntity<ChatResponse> response = assertDoesNotThrow(
                () -> controller.ask(request, new MockHttpServletRequest()),
                "超长消息不得触发 substring 越界");

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void longMessageIsHandledByStreamWithoutBoundsFailure() {
        ChatRequest request = chatRequest();
        request.setMessage("问".repeat(150));
        when(turnOperationService.prepare(any(ChatPrincipal.class), anyList(),
                any())).thenReturn(
                new ChatTurnOperationService.Prepared(
                        PRINCIPAL, null, null, null, null, false));
        when(ragChatService.chatEvents(any(ChatRequest.class), any(), any()))
                .thenReturn(reactor.core.publisher.Flux.empty());

        SseEmitter emitter = assertDoesNotThrow(
                () -> controller.stream(request, new MockHttpServletRequest(),
                        new org.springframework.mock.web.MockHttpServletResponse()),
                "超长消息不得触发 substring 越界");

        assertNotNull(emitter);
    }
}
