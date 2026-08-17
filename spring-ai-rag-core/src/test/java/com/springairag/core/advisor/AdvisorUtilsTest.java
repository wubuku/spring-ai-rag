package com.springairag.core.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdvisorUtilsTest {

    @Test
    void extractUserMessage_returnsLastUserMessage() {
        Prompt prompt = new Prompt(List.of(
                new UserMessage("hello"),
                new SystemMessage("system"),
                new UserMessage("world")
        ));
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        assertEquals("world", AdvisorUtils.extractUserMessage(request));
    }

    @Test
    void extractUserMessage_returnsNullForNullRequest() {
        assertNull(AdvisorUtils.extractUserMessage(null));
    }

    @Test
    void extractUserMessage_returnsNullForEmptyMessages() {
        Prompt prompt = new Prompt(List.of());
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        assertNull(AdvisorUtils.extractUserMessage(request));
    }

    @Test
    void extractUserMessage_returnsNullWhenNoUserMessage() {
        Prompt prompt = new Prompt(List.<Message>of(new SystemMessage("system")));
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        assertNull(AdvisorUtils.extractUserMessage(request));
    }

    @Test
    void extractUserMessage_skipsBlankUserMessages() {
        Prompt prompt = new Prompt(List.<Message>of(
                new UserMessage("  "),
                new UserMessage("valid")
        ));
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        assertEquals("valid", AdvisorUtils.extractUserMessage(request));
    }

    @Test
    void extractRetrievalQuery_prefersFocusedContextValue() {
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("找到 “风格基调” 相关的内容")))
                .context(Map.of(
                        QueryRewriteAdvisor.CTX_RETRIEVAL_QUERY,
                        "风格基调"))
                .build();

        assertEquals("风格基调", AdvisorUtils.extractRetrievalQuery(request));
    }

    @Test
    void extractRetrievalQuery_fallsBackToOriginalUserMessage() {
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("普通问题")))
                .build();

        assertEquals("普通问题", AdvisorUtils.extractRetrievalQuery(request));
    }
}
