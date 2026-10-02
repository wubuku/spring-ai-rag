package com.springairag.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import com.springairag.core.filter.ApiKeyAuthFilter;

/**
 * What a real servlet container actually does with a traversing request line.
 *
 * <p>Batch 794 gave {@code SecurityPathExclusions} a segment-aware, fail-closed
 * rule after finding that {@code path.startsWith("/actuator")} would exclude
 * {@code /actuator/../api/v1/rag/...}. The change was correct but its
 * justification was a stand-in: the comment said this project could not start a
 * container to measure the thing, so the rule was made independent of the
 * answer rather than the answer being established. That was the right call at
 * the time. Docker turned out to be available after all, so the measurement is
 * taken here instead of asserted around.
 *
 * <h2>What was measured</h2>
 * For {@code GET /actuator/../api/v1/rag/probe-protected}, Tomcat matches filter
 * mappings on the <b>normalised</b> path but {@code getRequestURI()} hands the
 * filter the <b>raw</b> request line:
 *
 * <pre>
 * requestURI = /actuator/../api/v1/rag/probe-protected
 * servletPath = /api/v1/rag/probe-protected
 * </pre>
 *
 * So the disagreement Batch 794 reasoned about is real, not hypothetical: the
 * auth filter genuinely runs on a request whose URI begins with an excluded
 * prefix while the router sees a different path. What the measurement also
 * shows is that on this stack the request then <b>404s either way</b> — Spring
 * MVC resolves the handler from the unnormalised URI, so the traversal finds no
 * mapping whether or not the exclusion skips authentication. The fail-closed
 * rule is therefore defence in depth here, not the only thing preventing
 * access, and both {@link #theTraversalDoesNotReachTheEndpointEvenWhenAuthenticated}
 * and the comment in {@code SecurityPathExclusions} now say so instead of
 * leaving the question open.
 *
 * <h2>What this probe does not prove</h2>
 * Deleting the fail-closed branch from {@code SecurityPathExclusions} leaves
 * every case here green, because routing 404s the traversal either way. So the
 * anonymous 404 is <b>not</b> evidence that the exclusion rule works — the
 * {@code SecurityPathExclusionsTest} unit suite is what actually pins that
 * rule, and it does fail when the branch is removed. What this probe uniquely
 * owns is the container-level mechanism: that the filter really is matched on
 * the normalised path, and really is handed the raw one.
 *
 * <h2>Why a raw socket</h2>
 * {@code HttpClient} and every browser normalise the URI before it leaves the
 * process, so they would answer a question about the client's behaviour rather
 * than the server's. MockMvc is equally unsuitable — it hands the filter chain a
 * pre-built request and never runs Tomcat's URI mapping. Only the bytes on the
 * wire decide what the container routes.
 *
 * <h2>Why the endpoint is defined here, and why there is no database</h2>
 * The thing under test is the disagreement between the path Tomcat matches
 * filter mappings with and the path {@code HttpServletRequest#getRequestURI()}
 * returns. That belongs to the servlet container, not to any controller and
 * certainly not to PostgreSQL. A real business controller would add variables —
 * its own annotations, its own interceptor — without sharpening the
 * measurement; a database would add container startup and a gate.
 *
 * <p>So the endpoint is local, routing is pinned to exactly one path, and the
 * only thing varying between cases is what the filter saw. That also means this
 * suite belongs to the default {@code mvn test} run rather than behind
 * {@code -Dpath-traversal.it.enabled}: a security invariant that only executes
 * when somebody remembers a flag is a weak invariant, and the thing being
 * asserted is about Tomcat, which is always present.
 *
 * <h2>Why the control case is load-bearing</h2>
 * The first version of this probe ran in an application context that registered
 * no controllers at all, so every case — traversing and ordinary alike — got a
 * 404 and three "passing" assertions were in fact only asserting
 * {@code 404 != 2xx}. The control case is what made that visible, and it is what
 * keeps the same blind spot from returning: it proves the endpoint exists, that
 * the filter really runs on it, and that a credential really does change the
 * answer.
 */
@SpringBootTest(
        classes = SecurityPathTraversalProbeTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.ai.openai.chat.enabled=false",
        "spring.ai.openai.embedding.enabled=false",
        "spring.main.banner-mode=off",
})
class SecurityPathTraversalProbeTest {

    private static final String API_KEY = "probe-key-001";
    private static final String PROTECTED = "/api/v1/rag/probe-protected";
    // Exactly the shape SecurityPathExclusions documents: one ".." that lands
    // back on the root and then walks into /api. A deeper prefix such as
    // "/actuator/../.." climbs above the context root, which Tomcat refuses
    // with 400 before the filter chain runs at all — that is a different (and
    // uninteresting) question, so the probe must not use it.
    private static final String TRAVERSAL = "/actuator/.." + PROTECTED;
    private static final String ENCODED_TRAVERSAL = "/actuator/%2e%2e" + PROTECTED;

    /**
     * What the container handed the filter chain, one entry per request.
     *
     * <p>This is the finding, recorded rather than inferred: it shows which URI
     * the filter mapping matched on and which URI the filter was then asked
     * about. An argument about "the raw path" is worth nothing if nobody has
     * looked at what the container actually handed over.
     */
    private static final Queue<String> OBSERVED = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger OBSERVED_COUNT = new AtomicInteger();

    @LocalServerPort
    int port;

    @Test
    @DisplayName("an ordinary request to the endpoint does require the key")
    void theControlCaseRequiresCredentials() throws IOException {
        Probe anonymous = rawGet(PROTECTED, null);
        Probe withKey = rawGet(PROTECTED, API_KEY);

        assertEquals(401, anonymous.status(),
                () -> "the control case should challenge an anonymous request, got "
                        + anonymous.status() + " (filter saw " + anonymous.observedUri() + ")");
        assertEquals(200, withKey.status(),
                () -> "the control case should serve an authenticated request, got "
                        + withKey.status() + " (filter saw " + withKey.observedUri() + ")");
    }

    @Test
    @DisplayName("a traversing request line cannot reach the protected endpoint unauthenticated")
    void traversalFromAnExcludedPrefixIsStillAuthenticated() throws IOException {
        Probe anonymous = rawGet(TRAVERSAL, null);

        assertNotEquals(2, anonymous.status() / 100,
                () -> "a traversing request line reached the protected endpoint with status "
                        + anonymous.status() + "; the path exclusion is bypassable"
                        + " (filter saw " + anonymous.observedUri() + ")");
    }

    @Test
    @DisplayName("the filter is matched on the normalised path but handed the raw request line")
    void theFilterAndTheRouterDisagreeAboutThePath() throws IOException {
        Probe probe = rawGet(TRAVERSAL, API_KEY);
        String observed = probe.observedUri();

        // The mechanism SecurityPathExclusions exists to defend, established as
        // a fact about this stack rather than as an assumption about all of
        // them. Both halves are asserted: the filter really did run (so the
        // url-pattern match happened against something), and the URI it was
        // asked about still carries ".." while servletPath — the value routing
        // is done on — does not.
        //
        // If a future Tomcat or Spring version closes this gap, this fails and
        // says why, rather than the case above quietly becoming a no-op.
        assertNotNull(observed,
                () -> "the auth filter never ran for the traversal, so the exclusion"
                        + " question was not exercised at all (status " + probe.status() + ")");
        assertTrue(observed.contains("requestURI=/actuator/../api/v1/rag/probe-protected"),
                () -> "expected the raw request line, got " + observed);
        assertTrue(observed.contains("servletPath=/api/v1/rag/probe-protected"),
                () -> "expected the container to have matched on the normalised path, got "
                        + observed);
    }

    @Test
    @DisplayName("on this stack the traversal 404s even with a valid credential")
    void theTraversalDoesNotReachTheEndpointEvenWhenAuthenticated() throws IOException {
        Probe authenticated = rawGet(TRAVERSAL, API_KEY);

        // The measured outcome, pinned so a future change cannot make it drift
        // unnoticed. Spring MVC resolves the handler from the unnormalised URI,
        // so the traversal finds no mapping and 404s. That makes the
        // fail-closed rule in SecurityPathExclusions defence in depth on this
        // stack rather than the only thing standing between the request and the
        // endpoint — which is worth knowing and worth keeping, because a proxy
        // in front, a different connector configuration, or a framework upgrade
        // could each remove the 404.
        //
        // The interesting direction is 200 without a credential (the bug). The
        // interesting other direction is 200 *with* one: that would mean the
        // exclusion had become load-bearing, and the comment in
        // SecurityPathExclusions that says the opposite would need rewriting.
        assertEquals(404, authenticated.status(),
                () -> "the authenticated traversal stopped returning 404; it returned "
                        + authenticated.status() + " (filter saw "
                        + authenticated.observedUri() + "). If that is a 200, handler"
                        + " resolution now follows the normalised path, the fail-closed"
                        + " rule has become load-bearing, and its comment must be"
                        + " corrected to say so.");
    }

    @Test
    @DisplayName("a percent-encoded traversal is treated the same way")
    void percentEncodedTraversalIsAlsoAuthenticated() throws IOException {
        Probe anonymous = rawGet(ENCODED_TRAVERSAL, null);
        Probe authenticated = rawGet(ENCODED_TRAVERSAL, API_KEY);

        assertNotEquals(2, anonymous.status() / 100,
                () -> "a percent-encoded traversal reached the protected endpoint with status "
                        + anonymous.status() + "; the path exclusion is bypassable"
                        + " (filter saw " + anonymous.observedUri() + ")");
        assertEquals(404, authenticated.status(),
                () -> "the authenticated percent-encoded traversal did not 404; it returned "
                        + authenticated.status() + " (filter saw "
                        + authenticated.observedUri() + ")");
    }

    /** Sends the request line verbatim; no client-side URI normalisation is involved. */
    private Probe rawGet(String rawPath, String apiKey) throws IOException {
        // Record where the queue ends before this request, so the observation
        // read back is this request's and not a previous test's.
        int before = OBSERVED_COUNT.get();
        int status;
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            StringBuilder request = new StringBuilder()
                    .append("GET ").append(rawPath).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(port).append("\r\n")
                    .append("Connection: close\r\n");
            if (apiKey != null) {
                request.append("X-API-Key: ").append(apiKey).append("\r\n");
            }
            request.append("\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = reader.readLine();
            if (statusLine == null) {
                status = -1;
            } else {
                String[] parts = statusLine.split(" ");
                status = parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
            }
        }
        return new Probe(status, observationAfter(before));
    }

    /**
     * Waits for the observation recorded by this request, so a case where the
     * container rejected the line before the chain ran reports {@code null}
     * instead of silently returning an earlier request's value.
     */
    private static String observationAfter(int before) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (OBSERVED_COUNT.get() <= before && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return OBSERVED.poll();
    }

    private record Probe(int status, String observedUri) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        // chat memory 的 JDBC 实现同样要 JdbcTemplate；本探测与对话记忆无关。
        "org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration",
        // readiness 分组在 application.yml 里 include 了 db contributor；没有数据源
        // 的上下文里健康端点自检会直接失败，这里整体关掉（探测只需要 Servlet 容器）。
        "org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration",
    })
    static class TestApplication {

        /**
         * The production filter, wired the way
         * {@code RagWebSecurityConfiguration} wires it: the same
         * {@code /api/*} url patterns and the same order ahead of rate
         * limiting. A database credential service is deliberately absent so the
         * legacy static key is the only credential that can succeed.
         */
        @Bean
        FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration() {
            FilterRegistrationBean<ApiKeyAuthFilter> registration =
                    new FilterRegistrationBean<>(new ApiKeyAuthFilter(API_KEY, true));
            registration.addUrlPatterns("/api/*", "/v1/*");
            registration.setOrder(-10);
            return registration;
        }

        /** Runs ahead of the auth filter and records what the container handed over. */
        @Bean
        FilterRegistrationBean<ObservedUriFilter> observedUriFilterRegistration() {
            FilterRegistrationBean<ObservedUriFilter> registration =
                    new FilterRegistrationBean<>(new ObservedUriFilter());
            registration.addUrlPatterns("/*");
            registration.setOrder(-100);
            return registration;
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping(PROTECTED)
        public Map<String, Object> protectedEndpoint() {
            return Map.of("served", true);
        }
    }

    /**
     * Records {@code getRequestURI()} together with what the container routed
     * on. Nothing else about the request is inspected; the point is only to
     * make the container's decision legible to the assertions.
     */
    static final class ObservedUriFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            OBSERVED.add("requestURI=" + request.getRequestURI()
                    + " servletPath=" + request.getServletPath()
                    + " pathInfo=" + request.getPathInfo());
            OBSERVED_COUNT.incrementAndGet();
            filterChain.doFilter(request, response);
        }
    }
}
