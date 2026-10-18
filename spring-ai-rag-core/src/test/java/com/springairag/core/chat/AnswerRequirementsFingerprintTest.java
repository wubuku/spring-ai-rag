package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.openai.OpenAiChatCompletionRequest;
import com.springairag.core.evaluation.AnswerContractValidator;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnswerRequirementsFingerprintTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String rules = "{\"rules\":[{\"id\":\"count\",\"type\":\"QUANTITY\",\"value\":\"0\",\"unit\":\"条\"}]}";

    @Test
    void omittedAndExplicitNullPreserveLegacyCanonicalButRulesChangeFingerprint() throws Exception {
        var request = new ChatRequest();
        request.setMessage("hello");
        request.setMode(ChatMode.PLAIN);
        var original = ChatRequestFingerprint.nativeRequest(request, mapper);
        assertFalse(original.canonical().has("answerRequirements"));
        request.setAnswerRequirements(mapper.nullNode());
        assertEquals(original.sha256(), ChatRequestFingerprint.nativeRequest(request, mapper).sha256());
        request.setAnswerRequirements(mapper.readTree(rules));
        assertNotEquals(original.sha256(), ChatRequestFingerprint.nativeRequest(request, mapper).sha256());
        request.setAnswerRequirements(mapper.readTree("{}"));
        assertThrows(RagException.class, () -> ChatRequestFingerprint.nativeRequest(request, mapper));
    }

    @Test
    void openAiOptionalRulesParticipateInFingerprintAndBothSpellingsWork() throws Exception {
        var request = mapper.readValue("{\"model\":\"rag-default\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}", OpenAiChatCompletionRequest.class);
        var old = ChatRequestFingerprint.openAiRequest(request, mapper);
        var options = mapper.readValue("{\"answer_requirements\":" + rules + "}", OpenAiChatCompletionRequest.RagOptions.class);
        request.setRag(options);
        assertNotEquals(old.sha256(), ChatRequestFingerprint.openAiRequest(request, mapper).sha256());
        assertEquals(options.getAnswerRequirements(), mapper.readValue("{\"answerRequirements\":" + rules + "}", OpenAiChatCompletionRequest.RagOptions.class).getAnswerRequirements());
    }

    @Test
    void allCommandCopiesRetainImmutableRequirements() throws Exception {
        var requirements = AnswerContractValidator.parse(mapper.readTree(rules));
        var command = ChatCommand.of("hello", null, ChatMode.PLAIN, null, null, null, null).withAnswerRequirements(requirements);
        assertSame(requirements, command.withTraceSession(null).withExecutionBudget(null).withFilters(null)
                .withModelCandidates(java.util.List.of()).withRequestTraceId("trace").withSessionId(command.sessionId()).answerRequirements());
        assertThrows(UnsupportedOperationException.class, () -> requirements.rules().clear());
    }
}
