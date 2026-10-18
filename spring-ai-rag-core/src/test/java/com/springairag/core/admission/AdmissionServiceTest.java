package com.springairag.core.admission;

import com.springairag.core.chat.BudgetedChatModel;
import com.springairag.core.chat.ChatExecutionBudget;
import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdmissionServiceTest {
    private final RagProperties properties = new RagProperties();
    private final AdmissionService service = new AdmissionService(properties, new SimpleMeterRegistry());

    @Test
    void principalProviderAndBackgroundPoolsAreIndependentAndLeasesAreIdempotent() {
        properties.getAdmission().setMaxRequestsPerPrincipal(1);
        var first = service.request("one");
        assertThrows(AdmissionRejectedException.class, () -> service.request("one"));
        service.request("two").close();
        first.close();
        first.close();
        assertEquals(0, service.active("request"));
        assertEquals(0, service.principalCount("request"));
        properties.getAdmission().setMaxModelCallsPerProvider(1);
        try (var online = service.model("one", "provider/model-a", false);
             var otherProvider = service.model("two", "other/model", false);
             var background = service.model("one", "provider/model-a", true)) {
            assertThrows(AdmissionRejectedException.class, () -> service.model("two", "provider/model-b", false));
            assertEquals(2, service.active("model"));
            assertEquals(1, service.active("background"));
        }
        assertEquals(0, service.principalCount("model"));
        assertEquals(0, service.principalCount("background"));
        try (var firstBackground = service.model("one", "provider/model", true);
             var secondBackground = service.model("two", "provider/model", true)) {
            assertThrows(AdmissionRejectedException.class, () -> service.model("three", "provider/model", true));
            service.model("three", "other/model", false).close();
        }
    }

    @Test
    void concurrentAcquisitionCannotExceedLimitAndRemovesKeys() throws Exception {
        properties.getAdmission().setMaxRequests(3);
        var executor = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempted = new CountDownLatch(12);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int index = 0; index < 12; index++) {
                String principal = "user-" + index;
                futures.add(executor.submit(() -> {
                    start.await();
                    AdmissionService.Lease lease = null;
                    try { lease = service.request(principal); }
                    catch (AdmissionRejectedException expected) { }
                    finally { attempted.countDown(); }
                    release.await();
                    if (lease != null) { lease.close(); }
                    return null;
                }));
            }
            start.countDown();
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertEquals(3, service.active("request"));
            release.countDown();
            for (var future : futures) { future.get(5, TimeUnit.SECONDS); }
            assertEquals(0, service.active("request"));
            assertEquals(0, service.principalCount("request"));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void modelRejectionPrecedesBudgetAndProviderAndAllTerminationsRelease() {
        properties.getAdmission().setMaxModelCalls(1);
        ChatModel delegate = mock(ChatModel.class);
        ChatExecutionBudget budget = budget();
        var model = new BudgetedChatModel(delegate, budget).withAdmission(service);
        var reservations = mock(com.springairag.core.budget.BudgetReservationService.class);
        var budgetedWithCosts = new BudgetedChatModel(delegate, budget, 0, 0, 0, 1000,
                new com.springairag.core.chat.JTokkitPromptTokenEstimator(),
                com.springairag.core.usage.LlmInvocationPurpose.CHAT,
                com.springairag.core.usage.LlmUsageRecorder.NOOP, null, "TEST", "provider/model", reservations)
                .withAdmission(service);
        clearInvocations(delegate);
        try (var busy = service.model("busy", "any/model", false)) {
            assertThrows(AdmissionRejectedException.class, () -> model.call(new Prompt("rejected")));
            assertThrows(AdmissionRejectedException.class, () -> model.stream(new Prompt("rejected")).blockLast());
            assertThrows(AdmissionRejectedException.class, () -> budgetedWithCosts.call(new Prompt("rejected")));
            assertThrows(AdmissionRejectedException.class, () -> budgetedWithCosts.stream(new Prompt("rejected")).blockLast());
            assertEquals(0, budget.modelCalls());
            verifyNoInteractions(reservations);
            verifyNoInteractions(delegate);
        }
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.never());
        var subscription = model.stream(new Prompt("cancel")).subscribe();
        assertEquals(1, service.active("model"));
        subscription.dispose();
        assertEquals(0, service.active("model"));
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.empty());
        model.stream(new Prompt("complete")).blockLast();
        assertEquals(0, service.active("model"));
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("async")));
        assertThrows(IllegalStateException.class, () -> model.stream(new Prompt("async fail")).blockLast());
        assertEquals(0, service.active("model"));
        when(delegate.stream(any(Prompt.class))).thenThrow(new IllegalStateException("sync"));
        assertThrows(IllegalStateException.class, () -> model.stream(new Prompt("fail")).blockLast());
        assertEquals(0, service.active("model"));
        when(delegate.call(any(Prompt.class))).thenThrow(new IllegalStateException("sync"));
        assertThrows(IllegalStateException.class, () -> model.call(new Prompt("fail")));
        assertEquals(0, service.active("model"));
    }

    @Test
    void backgroundIdentityIsCapturedNotInferredFromClientMetadata() {
        assertFalse(budget().backgroundWorkload());
        ChatExecutionBudget background;
        try (var scope = BackgroundWorkScope.open()) {
            background = budget();
            try (var nested = BackgroundWorkScope.open()) { assertTrue(budget().backgroundWorkload()); }
            assertTrue(BackgroundWorkScope.isActive());
        }
        assertTrue(background.backgroundWorkload());
        assertFalse(BackgroundWorkScope.isActive());
        assertFalse(budget().backgroundWorkload());
    }

    private ChatExecutionBudget budget() {
        return new ChatExecutionBudget(Instant.now().plusSeconds(30), 10, 10, 10, 10, 10, 1000);
    }
}
