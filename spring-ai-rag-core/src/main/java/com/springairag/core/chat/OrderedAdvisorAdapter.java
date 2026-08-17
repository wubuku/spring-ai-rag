package com.springairag.core.chat;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import reactor.core.scheduler.Scheduler;

/**
 * 保留自定义 Advisor 行为，同时把执行顺序收口到框架管理的稳定区间。
 */
final class OrderedAdvisorAdapter implements BaseAdvisor {

    private final BaseAdvisor delegate;
    private final String name;
    private final int order;

    OrderedAdvisorAdapter(BaseAdvisor delegate, String name, int order) {
        this.delegate = delegate;
        this.name = name;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(
            ChatClientRequest request,
            AdvisorChain advisorChain) {
        return delegate.before(request, advisorChain);
    }

    @Override
    public ChatClientResponse after(
            ChatClientResponse response,
            AdvisorChain advisorChain) {
        return delegate.after(response, advisorChain);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public Scheduler getScheduler() {
        return delegate.getScheduler();
    }
}
