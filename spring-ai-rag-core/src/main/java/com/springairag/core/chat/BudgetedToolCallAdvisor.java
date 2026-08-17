package com.springairag.core.chat;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.rag.ProjectDocumentRetriever;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * 在 Spring AI {@link ToolCallAdvisor} 的标准循环外增加服务端轮数预算。
 */
public final class BudgetedToolCallAdvisor extends ToolCallAdvisor {

    public BudgetedToolCallAdvisor(ToolCallingManager toolCallingManager, int order) {
        super(toolCallingManager, order, true, false);
    }

    @Override
    protected ChatClientResponse doAfterCall(
            ChatClientResponse response,
            CallAdvisorChain chain) {
        enforceBudget(response);
        return response;
    }

    @Override
    protected ChatClientResponse doAfterStream(
            ChatClientResponse response,
            StreamAdvisorChain chain) {
        enforceBudget(response);
        return response;
    }

    private void enforceBudget(ChatClientResponse response) {
        if (response == null
                || response.chatResponse() == null
                || !response.chatResponse().hasToolCalls()) {
            return;
        }
        Object value = response.context().get(ProjectDocumentRetriever.CONTEXT_KEY);
        if (value instanceof AuthorizedRetrievalContext context
                && !context.trace().tryBeginToolRound()) {
            throw new RagException(
                    ErrorCode.RETRIEVAL_FAILED,
                    "Agent tool-call round budget exhausted");
        }
    }
}
