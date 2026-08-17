package com.springairag.core.chat;

import com.springairag.api.dto.ChatRequest;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.extension.DomainExtensionRegistry;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatCommandMapperTest {

    private DomainExtensionRegistry domainExtensions;
    private ChatCommandMapper mapper;

    @BeforeEach
    void setUp() {
        domainExtensions = mock(DomainExtensionRegistry.class);
        mapper = new ChatCommandMapper(new RagProperties(), domainExtensions);
    }

    @Test
    void plainModeAllowsOmittedDtoRetrievalDefaults() {
        ChatRequest request = new ChatRequest("普通对话", "plain-session");
        request.setMode(ChatMode.PLAIN);

        ChatCommand command = mapper.map(
                request, RetrievalScope.unscoped(), ChatPrincipal.local());

        assertEquals(ChatMode.PLAIN, command.mode());
        assertFalse(request.isMaxResultsExplicitlySet());
        assertFalse(request.isUseHybridSearchExplicitlySet());
        assertFalse(request.isUseRerankExplicitlySet());
    }

    @Test
    void plainModeRejectsExplicitRetrievalOverrideWithTypedError() {
        ChatRequest request = new ChatRequest("普通对话", "plain-session");
        request.setMode(ChatMode.PLAIN);
        request.setUseRerank(true);

        RagException error = assertThrows(
                RagException.class,
                () -> mapper.map(
                        request,
                        RetrievalScope.unscoped(),
                        ChatPrincipal.local()));

        assertEquals(
                ErrorCode.RETRIEVAL_OPTIONS_NOT_ALLOWED,
                error.getErrorCodeEnum());
    }

    @Test
    void unknownDomainUsesTypedError() {
        ChatRequest request = new ChatRequest("领域问题", "domain-session");
        request.setDomainId("missing-domain");
        when(domainExtensions.hasDomain("missing-domain")).thenReturn(false);

        RagException error = assertThrows(
                RagException.class,
                () -> mapper.map(
                        request,
                        RetrievalScope.unscoped(),
                        ChatPrincipal.local()));

        assertEquals(ErrorCode.UNKNOWN_DOMAIN, error.getErrorCodeEnum());
    }
}
