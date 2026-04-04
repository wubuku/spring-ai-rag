package com.springairag.demo.component;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ComponentLevelController unit test — mocks ChatClient beans,
 * tests controller endpoints with proper MockMvc integration.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>GET /demo/component/ask — non-streaming RAG Q&A</li>
 *   <li>POST /demo/component/chat — multi-turn with session memory</li>
 *   <li>POST /demo/component/compare-memory — in-memory vs no-memory comparison</li>
 *   <li>Input validation — missing required parameters</li>
 * </ul>
 */
@WebMvcTest(controllers = ComponentLevelController.class)
@ActiveProfiles("test")
class ComponentLevelControllerTest {

    @MockBean(name = "ragChatClient")
    private ChatClient ragChatClient;

    @MockBean(name = "ragChatClientWithMemory")
    private ChatClient ragChatClientWithMemory;

    @Autowired
    private MockMvc mockMvc;

    // ─────────────────────────────────────────────────────────
    // GET /demo/component/ask — non-streaming RAG Q&A
    // ─────────────────────────────────────────────────────────

    @Test
    void ask_returnsOk_withQ() throws Exception {
        // ChatClient.prompt().user(q).call().content() 链式调用
        // 先 mock prompt() 返回 ChatClientRequestSpec
        ChatClient.ChatClientRequestSpec promptSpec =
                org.mockito.Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        org.mockito.Mockito.when(ragChatClient.prompt()).thenReturn(promptSpec);

        // mock user() 返回同一个 spec（支持链式）
        org.mockito.Mockito.when(promptSpec.user(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(promptSpec);

        // mock call() 返回 CallResponseSpec
        ChatClient.CallResponseSpec callSpec =
                org.mockito.Mockito.mock(ChatClient.CallResponseSpec.class);
        org.mockito.Mockito.when(promptSpec.call()).thenReturn(callSpec);

        // mock content() 返回回答文本
        org.mockito.Mockito.when(callSpec.content()).thenReturn("Spring AI 是一个 AI 框架");

        mockMvc.perform(get("/demo/component/ask")
                        .param("q", "什么是 Spring AI"))
                .andExpect(status().isOk())
                .andExpect(content().string("Spring AI 是一个 AI 框架"));
    }

    @Test
    void ask_returnsBadRequest_whenMissingQ() throws Exception {
        mockMvc.perform(get("/demo/component/ask"))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────
    // POST /demo/component/chat — multi-turn with session
    // ─────────────────────────────────────────────────────────

    @Test
    void chat_returnsAnswerAndSessionId() throws Exception {
        ChatClient.ChatClientRequestSpec promptSpec =
                org.mockito.Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        org.mockito.Mockito.when(ragChatClientWithMemory.prompt()).thenReturn(promptSpec);
        org.mockito.Mockito.when(promptSpec.user(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(promptSpec);

        ChatClient.AdvisorSpec advisorSpec =
                org.mockito.Mockito.mock(ChatClient.AdvisorSpec.class);
        org.mockito.Mockito.when(promptSpec.advisors(org.mockito.ArgumentMatchers.any(java.util.function.Consumer.class))).thenReturn(promptSpec);

        ChatClient.CallResponseSpec callSpec =
                org.mockito.Mockito.mock(ChatClient.CallResponseSpec.class);
        org.mockito.Mockito.when(promptSpec.call()).thenReturn(callSpec);
        org.mockito.Mockito.when(callSpec.content()).thenReturn("这是 RAG 回答");

        mockMvc.perform(post("/demo/component/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("这是 RAG 回答"))
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.mode").value("ChatClient + RAG Advisors + Memory"));
    }

    @Test
    void chat_usesProvidedSessionId() throws Exception {
        ChatClient.ChatClientRequestSpec promptSpec =
                org.mockito.Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        org.mockito.Mockito.when(ragChatClientWithMemory.prompt()).thenReturn(promptSpec);
        org.mockito.Mockito.when(promptSpec.user(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(promptSpec);
        org.mockito.Mockito.when(promptSpec.advisors(org.mockito.ArgumentMatchers.any(java.util.function.Consumer.class))).thenReturn(promptSpec);

        ChatClient.CallResponseSpec callSpec =
                org.mockito.Mockito.mock(ChatClient.CallResponseSpec.class);
        org.mockito.Mockito.when(promptSpec.call()).thenReturn(callSpec);
        org.mockito.Mockito.when(callSpec.content()).thenReturn("回答");

        mockMvc.perform(post("/demo/component/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"test\", \"sessionId\": \"my-session-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("my-session-123"));
    }

    // ─────────────────────────────────────────────────────────
    // POST /demo/component/compare-memory — in-memory vs no-memory
    // ─────────────────────────────────────────────────────────

    @Test
    void compareMemory_returnsBothAnswers() throws Exception {
        // ragChatClient (no memory)
        ChatClient.ChatClientRequestSpec spec1 =
                org.mockito.Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        org.mockito.Mockito.when(ragChatClient.prompt()).thenReturn(spec1);
        org.mockito.Mockito.when(spec1.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(spec1);
        ChatClient.CallResponseSpec callSpec1 =
                org.mockito.Mockito.mock(ChatClient.CallResponseSpec.class);
        org.mockito.Mockito.when(spec1.call()).thenReturn(callSpec1);
        org.mockito.Mockito.when(callSpec1.content()).thenReturn("无记忆响应");

        // ragChatClientWithMemory (with memory)
        ChatClient.ChatClientRequestSpec spec2 =
                org.mockito.Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        org.mockito.Mockito.when(ragChatClientWithMemory.prompt()).thenReturn(spec2);
        org.mockito.Mockito.when(spec2.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(spec2);
        org.mockito.Mockito.when(spec2.advisors(org.mockito.ArgumentMatchers.any(java.util.function.Consumer.class))).thenReturn(spec2);
        ChatClient.CallResponseSpec callSpec2 =
                org.mockito.Mockito.mock(ChatClient.CallResponseSpec.class);
        org.mockito.Mockito.when(spec2.call()).thenReturn(callSpec2);
        org.mockito.Mockito.when(callSpec2.content()).thenReturn("带记忆响应");

        mockMvc.perform(post("/demo/component/compare-memory")
                        .param("sessionId", "compare-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q1\": \"我叫张三\", \"q2\": \"我叫什么名字？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.q1").value("我叫张三"))
                .andExpect(jsonPath("$.q2").value("我叫什么名字？"))
                .andExpect(jsonPath("$.a2_no_memory").value("无记忆响应"))
                .andExpect(jsonPath("$.a2_with_memory").value("带记忆响应"))
                .andExpect(jsonPath("$.note").exists());
    }

    @Test
    void compareMemory_usesDefaultsWhenBodyEmpty() throws Exception {
        ChatClient.ChatClientRequestSpec spec1 =
                org.mockito.Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        org.mockito.Mockito.when(ragChatClient.prompt()).thenReturn(spec1);
        org.mockito.Mockito.when(spec1.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(spec1);
        ChatClient.CallResponseSpec callSpec1 =
                org.mockito.Mockito.mock(ChatClient.CallResponseSpec.class);
        org.mockito.Mockito.when(spec1.call()).thenReturn(callSpec1);
        org.mockito.Mockito.when(callSpec1.content()).thenReturn("响应1");

        ChatClient.ChatClientRequestSpec spec2 =
                org.mockito.Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        org.mockito.Mockito.when(ragChatClientWithMemory.prompt()).thenReturn(spec2);
        org.mockito.Mockito.when(spec2.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(spec2);
        org.mockito.Mockito.when(spec2.advisors(org.mockito.ArgumentMatchers.any(java.util.function.Consumer.class))).thenReturn(spec2);
        ChatClient.CallResponseSpec callSpec2 =
                org.mockito.Mockito.mock(ChatClient.CallResponseSpec.class);
        org.mockito.Mockito.when(spec2.call()).thenReturn(callSpec2);
        org.mockito.Mockito.when(callSpec2.content()).thenReturn("响应2");

        // Empty body → uses default q1/q2
        mockMvc.perform(post("/demo/component/compare-memory")
                        .param("sessionId", "session-x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.q1").value("我叫张三"))
                .andExpect(jsonPath("$.q2").value("我叫什么名字？"));
    }

    @Test
    void compareMemory_returnsBadRequest_whenMissingSessionId() throws Exception {
        mockMvc.perform(post("/demo/component/compare-memory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q1\": \"a\", \"q2\": \"b\"}"))
                .andExpect(status().isBadRequest());
    }
}
