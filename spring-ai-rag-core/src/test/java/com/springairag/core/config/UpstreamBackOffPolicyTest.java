package com.springairag.core.config;

import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;
import org.springframework.retry.context.RetryContextSupport;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UpstreamBackOffPolicyTest {
    private final Instant now = Instant.parse("2026-10-18T00:00:00Z");

    @Test
    void jitterAndRetryAfterUseCurrentAttemptAndNoRealSleep() {
        var properties = new RagRetryProperties();
        properties.setInitialBackoffMs(100);
        properties.setJitterFactor(0.5);
        List<Long> waits = new ArrayList<>();
        var policy = new UpstreamBackOffPolicy(properties, waits::add, () -> 1, Clock.fixed(now, ZoneOffset.UTC));
        var context = new RetryContextSupport(null);
        context.registerThrowable(new org.springframework.ai.retry.TransientAiException("temporary"));
        var state = policy.start(context);
        policy.backOff(state);
        assertEquals(List.of(150L), waits);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "1");
        context.registerThrowable(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "limited", headers, new byte[0], null));
        policy.backOff(state);
        assertEquals(List.of(150L, 1000L), waits);
    }

    @Test
    void insufficientDeadlineNeverSleepsOrRetries() {
        var properties = new RagRetryProperties();
        List<Long> waits = new ArrayList<>();
        var policy = new UpstreamBackOffPolicy(properties, waits::add, () -> 0.5, Clock.fixed(now, ZoneOffset.UTC));
        var context = new RetryContextSupport(null);
        context.registerThrowable(new org.springframework.ai.retry.TransientAiException("temporary"));
        context.setAttribute(UpstreamBackOffPolicy.DEADLINE_ATTRIBUTE, now.plusMillis(50));
        assertEquals("CHAT_TIMEOUT", assertThrows(RagException.class, () -> policy.backOff(policy.start(context))).getErrorCode());
        assertTrue(waits.isEmpty());
    }

    @Test
    void excessiveRetryAfterDoesNotRetryEarlierThanProviderRequested() {
        List<Long> waits = new ArrayList<>();
        var policy = new UpstreamBackOffPolicy(new RagRetryProperties(), waits::add, () -> 0.5, Clock.fixed(now, ZoneOffset.UTC));
        var context = new RetryContextSupport(null);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "9999");
        var error = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "limited", headers, new byte[0], null);
        context.registerThrowable(error);
        assertSame(error, assertThrows(RuntimeException.class, () -> policy.backOff(policy.start(context))));
        assertTrue(waits.isEmpty());
    }
}
