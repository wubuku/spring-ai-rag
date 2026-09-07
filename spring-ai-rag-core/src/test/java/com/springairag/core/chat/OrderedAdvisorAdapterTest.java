package com.springairag.core.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import reactor.core.scheduler.Scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 顺序收口适配器：名称与顺序被收口，行为与调度器透传给被包装者。 */
class OrderedAdvisorAdapterTest {

    private BaseAdvisor delegate;
    private AdvisorChain chain;
    private ChatClientRequest request;
    private ChatClientResponse response;
    private Scheduler scheduler;
    private OrderedAdvisorAdapter adapter;

    @BeforeEach
    void setUp() {
        delegate = mock(BaseAdvisor.class);
        chain = mock(AdvisorChain.class);
        request = mock(ChatClientRequest.class);
        response = mock(ChatClientResponse.class);
        scheduler = mock(Scheduler.class);
        adapter = new OrderedAdvisorAdapter(delegate, "rag-ordered-advisor", 42);
    }

    @Test
    void exposesTheStableManagedNameInsteadOfTheDelegateName() {
        when(delegate.getName()).thenReturn("custom-internal-name");

        assertEquals("rag-ordered-advisor", adapter.getName());
    }

    @Test
    void exposesTheManagedOrderInsteadOfTheDelegateOrder() {
        when(delegate.getOrder()).thenReturn(-100);

        assertEquals(42, adapter.getOrder());
    }

    @Test
    void beforeAndAfterDelegateToTheWrappedAdvisor() {
        when(delegate.before(request, chain)).thenReturn(request);
        when(delegate.after(response, chain)).thenReturn(response);

        assertSame(request, adapter.before(request, chain));
        assertSame(response, adapter.after(response, chain));
        verify(delegate).before(request, chain);
        verify(delegate).after(response, chain);
    }

    @Test
    void schedulerIsDelegatedToTheWrappedAdvisor() {
        when(delegate.getScheduler()).thenReturn(scheduler);

        assertSame(scheduler, adapter.getScheduler());
    }
}
