package com.springairag.core.chat;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.rag.ProjectDocumentRetriever;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.List;

/**
 * 在 Spring AI {@link ToolCallAdvisor} 的标准循环外增加服务端轮数预算。
 */
public final class BudgetedToolCallAdvisor extends ToolCallAdvisor {

    public BudgetedToolCallAdvisor(ToolCallingManager toolCallingManager, int order) {
        super(toolCallingManager, order, true, false);
    }

    @Override
    protected ChatClientRequest doInitializeLoop(
            ChatClientRequest request,
            CallAdvisorChain chain) {
        return withTranscriptCollector(request);
    }

    @Override
    protected ChatClientRequest doInitializeLoopStream(
            ChatClientRequest request,
            StreamAdvisorChain chain) {
        return withTranscriptCollector(request);
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

    @Override
    protected List<Message> doGetNextInstructionsForToolCall(
            ChatClientRequest request,
            ChatClientResponse response,
            ToolExecutionResult result) {
        collector(request).record(response, result);
        return super.doGetNextInstructionsForToolCall(
                request, response, result);
    }

    @Override
    protected List<Message> doGetNextInstructionsForToolCallStream(
            ChatClientRequest request,
            ChatClientResponse response,
            ToolExecutionResult result) {
        collector(request).record(response, result);
        return super.doGetNextInstructionsForToolCallStream(
                request, response, result);
    }

    private ChatClientRequest withTranscriptCollector(
            ChatClientRequest request) {
        if (request.context().get(ToolTranscriptCollector.CONTEXT_KEY)
                instanceof ToolTranscriptCollector) {
            return request;
        }
        return request.mutate()
                .context(
                        ToolTranscriptCollector.CONTEXT_KEY,
                        new ToolTranscriptCollector())
                .build();
    }

    private ToolTranscriptCollector collector(ChatClientRequest request) {
        Object value = request.context().get(
                ToolTranscriptCollector.CONTEXT_KEY);
        if (value instanceof ToolTranscriptCollector collector) {
            return collector;
        }
        throw new IllegalStateException(
                "Tool transcript collector is missing from advisor context");
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
