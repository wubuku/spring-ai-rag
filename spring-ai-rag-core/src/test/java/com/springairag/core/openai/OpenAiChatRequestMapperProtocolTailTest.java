package com.springairag.core.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.chat.ChatInputMessage;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAiChatRequestMapper 协议校验矩阵（Batch 410）：必填字段、
 * 消息条数/角色/内容形态、n=1 约束、不支持参数与未知字段、PLAIN
 * 模式下 scope/document_ids/集合头/filters 的拒绝、memory 大小写
 * 归一、多段 text 内容拼接与 latestUser 取最后一条 user。
 */
class OpenAiChatRequestMapperProtocolTailTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private OpenAiChatRequestMapper service;

    @BeforeEach
    void setUp() {
        service = new OpenAiChatRequestMapper(
                mockRegistry(), mockScopeAdapter(), new RagProperties());
    }

    private OpenAiModelAliasRegistry mockRegistry() {
        return org.mockito.Mockito.mock(OpenAiModelAliasRegistry.class);
    }

    private OpenAiRequestRetrievalScopeAdapter mockScopeAdapter() {
        return org.mockito.Mockito.mock(OpenAiRequestRetrievalScopeAdapter.class);
    }

    private OpenAiChatCompletionRequest.Message message(String role, String text) {
        OpenAiChatCompletionRequest.Message message =
                new OpenAiChatCompletionRequest.Message();
        message.setRole(role);
        try {
            message.setContent(mapper.readTree(
                    mapper.writeValueAsBytes(text)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return message;
    }

    private OpenAiChatCompletionRequest validRequest() {
        OpenAiChatCompletionRequest request = new OpenAiChatCompletionRequest();
        request.setModel("gpt-test");
        request.setMessages(List.of(
                message("system", "be brief"),
                message("user", "hello")));
        return request;
    }

    private OpenAiProtocolException validateFailing(
            OpenAiChatCompletionRequest request) {
        return assertThrows(OpenAiProtocolException.class,
                () -> service.validateDeclaration(request));
    }

    private OpenAiProtocolException validateFailing(
            OpenAiChatCompletionRequest request, List<String> headers) {
        return assertThrows(OpenAiProtocolException.class,
                () -> service.validateDeclaration(request, headers));
    }

    // ── validateRequest ────────────────────────────────────────────

    @Test
    void nullBodyIsRejected() {
        assertEquals("Request body is required",
                validateFailing(null).getMessage());
    }

    @Test
    void modelIsRequired() {
        OpenAiChatCompletionRequest request = validRequest();
        request.setModel("  ");
        assertEquals("model is required",
                validateFailing(request).getMessage());
    }

    @Test
    void messagesMustContainAtLeastOneItem() {
        OpenAiChatCompletionRequest request = validRequest();
        request.setMessages(List.of());
        assertEquals("messages must contain at least one message",
                validateFailing(request).getMessage());
    }

    @Test
    void messagesAboveLimitAreRejected() {
        OpenAiChatCompletionRequest request = validRequest();
        List<OpenAiChatCompletionRequest.Message> messages =
                new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            messages.add(message("user", "m" + i));
        }
        request.setMessages(messages);
        assertTrue(validateFailing(request).getMessage()
                .contains("more than 100 items"));
    }

    @Test
    void onlyNEqualsOneIsSupported() {
        OpenAiChatCompletionRequest request = validRequest();
        request.setN(2);
        assertEquals("Only n=1 is supported",
                validateFailing(request).getMessage());
    }

    @Test
    void samplingParametersAreRejected() {
        OpenAiChatCompletionRequest request = validRequest();
        request.setTemperature(0.7);
        assertTrue(validateFailing(request).getMessage()
                .contains("temperature is not supported"));

        OpenAiChatCompletionRequest withTools = validRequest();
        withTools.setTools(List.of());
        assertTrue(validateFailing(withTools).getMessage()
                .contains("tools is not supported"));
    }

    @Test
    void unknownTopLevelFieldsAreRejected() {
        OpenAiChatCompletionRequest request = validRequest();
        request.putAdditionalProperty("logit_bias",
                mapper.nullNode());
        assertTrue(validateFailing(request).getMessage()
                .contains("logit_bias is not supported"));
    }

    // ── PLAIN 模式约束 ─────────────────────────────────────────────

    @Test
    void plainModeRejectsScopeDocumentIdsAndCollectionHeaders() {
        OpenAiChatCompletionRequest withScope = validRequest();
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        rag.setMode(com.springairag.api.enums.ChatMode.PLAIN);
        rag.setScope(new OpenAiChatCompletionRequest.Scope());
        withScope.setRag(rag);
        assertTrue(validateFailing(withScope).getMessage()
                .contains("rag.scope is not allowed"));

        OpenAiChatCompletionRequest withDocIds = validRequest();
        OpenAiChatCompletionRequest.RagOptions ragDocIds =
                new OpenAiChatCompletionRequest.RagOptions();
        ragDocIds.setMode(com.springairag.api.enums.ChatMode.PLAIN);
        ragDocIds.setDocumentIds(List.of(1L));
        withDocIds.setRag(ragDocIds);
        assertTrue(validateFailing(withDocIds).getMessage()
                .contains("rag.document_ids is not allowed"));

        // 集合头拒绝同样只在 PLAIN 模式下触发。
        OpenAiChatCompletionRequest plainWithHeader = validRequest();
        OpenAiChatCompletionRequest.RagOptions plain =
                new OpenAiChatCompletionRequest.RagOptions();
        plain.setMode(com.springairag.api.enums.ChatMode.PLAIN);
        plainWithHeader.setRag(plain);
        assertTrue(validateFailing(plainWithHeader, List.of("kb"))
                .getMessage().contains("X-RAG-Collection-Key is not allowed"));
    }

    @Test
    void plainModeRejectsNonEmptyFilters() throws Exception {
        OpenAiChatCompletionRequest request = validRequest();
        OpenAiChatCompletionRequest.RagOptions rag =
                new OpenAiChatCompletionRequest.RagOptions();
        rag.setMode(com.springairag.api.enums.ChatMode.PLAIN);
        OpenAiChatCompletionRequest.Filters filters =
                new OpenAiChatCompletionRequest.Filters();
        filters.setMetadataContains(
                mapper.readTree("{\"env\":\"prod\"}"));
        rag.setFilters(filters);
        request.setRag(rag);
        assertTrue(validateFailing(request).getMessage()
                .contains("rag.filters is not allowed"));
    }

    @Test
    void memoryIsNormalizedCaseInsensitively() throws Exception {
        OpenAiChatCompletionRequest invalid = validRequest();
        OpenAiChatCompletionRequest.RagOptions badMemory =
                new OpenAiChatCompletionRequest.RagOptions();
        badMemory.setMemory("wizard");
        invalid.setRag(badMemory);
        assertTrue(validateFailing(invalid).getMessage()
                .contains("rag.memory must be STATELESS or SERVER"));

        OpenAiChatCompletionRequest valid = validRequest();
        OpenAiChatCompletionRequest.RagOptions goodMemory =
                new OpenAiChatCompletionRequest.RagOptions();
        goodMemory.setMemory(" server ");
        valid.setRag(goodMemory);
        assertEquals("hello",
                service.validateDeclaration(valid).latestUser());
    }

    // ── parseMessages / parseRole / parseTextContent ───────────────

    @Test
    void nullMessageElementIsRejected() {
        OpenAiChatCompletionRequest request = validRequest();
        List<OpenAiChatCompletionRequest.Message> messages =
                new ArrayList<>();
        messages.add(null);
        request.setMessages(messages);
        assertTrue(validateFailing(request).getMessage()
                .contains("message must not be null"));
    }

    @Test
    void messageUnknownFieldsAreRejected() {
        OpenAiChatCompletionRequest request = validRequest();
        OpenAiChatCompletionRequest.Message named =
                message("user", "hi");
        named.setName("customer");
        request.setMessages(List.of(named));
        assertTrue(validateFailing(request).getMessage()
                .contains("name, tool/function calls"));
    }

    @Test
    void roleIsRequiredAndConstrained() {
        OpenAiChatCompletionRequest nullRole = validRequest();
        nullRole.setMessages(List.of(message(null, "hi")));
        assertTrue(validateFailing(nullRole).getMessage()
                .contains("role is required"));

        OpenAiChatCompletionRequest wizard = validRequest();
        wizard.setMessages(List.of(message("wizard", "hi")));
        assertTrue(validateFailing(wizard).getMessage()
                .contains("role must be system, developer, user, or assistant"));
    }

    @Test
    void contentShapesAreStrictlyValidated() throws Exception {
        OpenAiChatCompletionRequest missing = validRequest();
        OpenAiChatCompletionRequest.Message noContent =
                new OpenAiChatCompletionRequest.Message();
        noContent.setRole("user");
        missing.setMessages(List.of(noContent));
        assertTrue(validateFailing(missing).getMessage()
                .contains("content is required"));

        OpenAiChatCompletionRequest numeric = validRequest();
        OpenAiChatCompletionRequest.Message numberContent =
                new OpenAiChatCompletionRequest.Message();
        numberContent.setRole("user");
        numberContent.setContent(mapper.readTree("42"));
        numeric.setMessages(List.of(numberContent));
        assertTrue(validateFailing(numeric).getMessage()
                .contains("content must be a string or an array"));

        OpenAiChatCompletionRequest badPart = validRequest();
        OpenAiChatCompletionRequest.Message arrayContent =
                new OpenAiChatCompletionRequest.Message();
        arrayContent.setRole("user");
        arrayContent.setContent(mapper.readTree(
                "[{\"type\":\"image_url\"}]"));
        badPart.setMessages(List.of(arrayContent));
        assertTrue(validateFailing(badPart).getMessage()
                .contains("content parts are supported"));
    }

    @Test
    void multipartTextContentIsJoinedAndLatestUserWins() throws Exception {
        OpenAiChatCompletionRequest request = validRequest();
        OpenAiChatCompletionRequest.Message multiPart =
                new OpenAiChatCompletionRequest.Message();
        multiPart.setRole("user");
        multiPart.setContent(mapper.readTree(
                "[{\"type\":\"text\",\"text\":\"part one\"},"
                        + "{\"type\":\"text\",\"text\":\"part two\"}]"));
        OpenAiChatCompletionRequest.Message finalUser =
                message("user", "final question");
        request.setMessages(List.of(
                message("system", "sys"),
                multiPart,
                message("assistant", "ack"),
                finalUser));

        OpenAiChatRequestMapper.Declaration declaration =
                service.validateDeclaration(request);

        // 多段 text 以换行拼接；latestUser 取最后一条 user。
        ChatInputMessage joined = declaration.inputMessages().get(1);
        assertEquals("part one\npart two", joined.content());
        assertEquals("final question", declaration.latestUser());
    }

    @Test
    void requestsWithoutUserMessageAreRejected() {
        OpenAiChatCompletionRequest request = validRequest();
        request.setMessages(List.of(
                message("system", "sys"),
                message("assistant", "ack")));
        assertTrue(validateFailing(request).getMessage()
                .contains("at least one user message"));
    }
}
