package com.springairag.core.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

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
}
