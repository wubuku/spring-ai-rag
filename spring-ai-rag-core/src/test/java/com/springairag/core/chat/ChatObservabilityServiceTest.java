package com.springairag.core.chat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 低基数 Chat 指标：registry 在场逐一计数、缺省时安全静默。 */
class ChatObservabilityServiceTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<io.micrometer.core.instrument.MeterRegistry>
            provider(SimpleMeterRegistry registry) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    @Test
    void incrementsEachLowCardinalityCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatObservabilityService service =
                new ChatObservabilityService(provider(registry));

        service.providerCall();
        service.providerCall();
        service.claimed();
        service.replayed();
        service.failed();
        service.inProgress();

        assertEquals(2.0,
                registry.get("rag.chat.provider.calls.total").counter().count());
        assertEquals(1.0,
                registry.get("rag.chat.turns.claimed.total").counter().count());
        assertEquals(1.0,
                registry.get("rag.chat.turns.replayed.total").counter().count());
        assertEquals(1.0,
                registry.get("rag.chat.turns.failed.total").counter().count());
        assertEquals(1.0,
                registry.get("rag.chat.turns.in_progress.total").counter().count());
    }

    @Test
    void isSilentWhenNoMeterRegistryIsAvailable() {
        ChatObservabilityService service =
                new ChatObservabilityService(provider(null));

        assertDoesNotThrow(() -> {
            service.providerCall();
            service.claimed();
            service.replayed();
            service.failed();
            service.inProgress();
        });
    }
}
