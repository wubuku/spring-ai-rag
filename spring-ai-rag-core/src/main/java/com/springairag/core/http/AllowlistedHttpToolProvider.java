package com.springairag.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.service.RagChatToolContextKeys;
import com.springairag.api.service.RagChatToolPolicy;
import com.springairag.api.service.RagChatToolProvider;
import com.springairag.api.service.RagChatToolRequestContext;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.skill.RuntimeSkill;
import com.springairag.core.skill.RuntimeSkillCatalog;
import com.springairag.core.skill.RuntimeSkillLoadSession;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.util.Timeout;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Server-owned, read-only HTTP tools bound to configured endpoints.
 *
 * <p>The model never supplies a URL, method, header, credential, or redirect
 * target. It supplies only the query parameters declared by one endpoint.
 * Skills describe how to use an endpoint, but the endpoint allowlist remains
 * the authorization boundary.</p>
 */
@Component
public final class AllowlistedHttpToolProvider implements RagChatToolProvider {

    private final RuntimeSkillCatalog skillCatalog;
    private final RagChatProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpTransport transport;
    private final AddressResolver addressResolver;
    private final List<EndpointCallback> callbacks;
    private final Map<String, RagChatProperties.HttpEndpointProperties> endpoints;

    @Autowired
    public AllowlistedHttpToolProvider(
            RuntimeSkillCatalog skillCatalog,
            RagChatProperties properties,
            ObjectMapper objectMapper) {
        this(
                skillCatalog,
                properties,
                objectMapper,
                new PinnedDnsHttpTransport(),
                InetAddress::getAllByName);
    }

    AllowlistedHttpToolProvider(
            RuntimeSkillCatalog skillCatalog,
            RagChatProperties properties,
            ObjectMapper objectMapper,
            HttpTransport transport,
            AddressResolver addressResolver) {
        this.skillCatalog = skillCatalog;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transport = transport;
        this.addressResolver = addressResolver;
        this.endpoints = properties.getHttpTools().isEnabled()
                ? validateAndFreeze(properties.getHttpTools().getEndpoints())
                : Map.of();
        this.callbacks = buildCallbacks();
    }

    @PreDestroy
    void closeTransport() {
        try {
            transport.close();
        } catch (Exception ignored) {
            // Application shutdown remains best effort.
        }
    }

    @Override
    public String getName() {
        return "allowlisted-http";
    }

    @Override
    public int getOrder() {
        return -90;
    }

    @Override
    public Set<ChatMode> supportedModes() {
        return Set.of(ChatMode.AGENT);
    }

    @Override
    public List<ToolCallback> getToolCallbacks() {
        return properties.getHttpTools().isEnabled()
                ? List.copyOf(callbacks)
                : List.of();
    }

    @Override
    public Map<String, RagChatToolPolicy> getToolPolicies() {
        if (!properties.getHttpTools().isEnabled()) {
            return Map.of();
        }
        Map<String, RagChatToolPolicy> result = new LinkedHashMap<>();
        for (RagChatProperties.HttpEndpointProperties endpoint
                : endpoints.values()) {
            result.put(
                    endpoint.getToolName(),
                    new RagChatToolPolicy(
                            RagChatToolPolicy.Effect.READ_ONLY,
                            endpoint.getMaxCallsPerRequest(),
                            endpoint.getMaxResultCharacters(),
                            Duration.ofMillis(endpoint.getTimeoutMs())));
        }
        return Map.copyOf(result);
    }

    private Map<String, RagChatProperties.HttpEndpointProperties>
            validateAndFreeze(
                    List<RagChatProperties.HttpEndpointProperties> configured) {
        RuntimeSkillCatalog.Snapshot skillSnapshot = skillCatalog.snapshot();
        if (skillSnapshot != null && !skillSnapshot.healthy()) {
            return Map.of();
        }
        Map<String, RagChatProperties.HttpEndpointProperties> result =
                new LinkedHashMap<>();
        for (RagChatProperties.HttpEndpointProperties endpoint
                : configured == null ? List.<RagChatProperties.HttpEndpointProperties>of()
                : configured) {
            if (endpoint == null || endpoint.getToolName() == null
                    || !resultKeyAvailable(result, endpoint.getToolName())) {
                throw new IllegalStateException(
                        "Duplicate or blank allowlisted HTTP tool name");
            }
            RuntimeSkill skill = skillCatalog.find(endpoint.getSkillName());
            if (skill == null
                    || !skill.capabilities().contains(endpoint.getCapability())) {
                throw new IllegalStateException(
                        "Allowlisted HTTP endpoint capability is not declared by "
                                + "its registered Skill");
            }
            result.put(endpoint.getToolName(), endpoint);
        }
        return Map.copyOf(result);
    }

    private boolean resultKeyAvailable(
            Map<String, RagChatProperties.HttpEndpointProperties> result,
            String name) {
        return name != null && !name.isBlank() && !result.containsKey(name);
    }

    private List<EndpointCallback> buildCallbacks() {
        return endpoints.values().stream()
                .map(EndpointCallback::new)
                .toList();
    }

    private final class EndpointCallback implements ToolCallback {
        private final RagChatProperties.HttpEndpointProperties endpoint;
        private final ToolDefinition definition;

        private EndpointCallback(
                RagChatProperties.HttpEndpointProperties endpoint) {
            this.endpoint = endpoint;
            this.definition = definition(endpoint);
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(false).build();
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException(
                    "Missing server-owned HTTP tool context");
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            RagChatToolRequestContext requestContext = request(toolContext);
            RuntimeSkillLoadSession skillSession = skillSession(toolContext);
            if (skillSession == null
                    || !skillSession.isLoaded(endpoint.getSkillName())) {
                return error("skill_not_loaded");
            }
            JsonNode input;
            try {
                input = parseInput(toolInput);
            } catch (IllegalArgumentException e) {
                return error("invalid_tool_input");
            }
            Map<String, String> query = new LinkedHashMap<>();
            for (RagChatProperties.HttpQueryParameterProperties parameter
                    : endpoint.getQueryParameters()) {
                JsonNode value = input.get(parameter.getName());
                if (value == null || value.isNull()
                        || !value.isTextual()
                        || value.textValue().isBlank()) {
                    if (parameter.isRequired()) {
                        return error("missing_query_parameter");
                    }
                    continue;
                }
                String text = value.textValue();
                if (text.length() > parameter.getMaxLength()
                        || text.chars().anyMatch(Character::isISOControl)) {
                    return error("query_parameter_rejected");
                }
                query.put(parameter.getName(), text);
            }
            URI uri;
            try {
                uri = endpointUri(endpoint, query);
            } catch (IllegalArgumentException e) {
                return error("request_uri_rejected");
            }
            InetAddress[] validatedAddresses =
                    resolvePublicTarget(uri.getHost());
            if (validatedAddresses == null) {
                return error("network_target_rejected");
            }

            Duration timeout = Duration.ofMillis(endpoint.getTimeoutMs());
            if (requestContext.deadline() != null) {
                long remaining = Duration.between(
                        java.time.Instant.now(),
                        requestContext.deadline()).toMillis();
                if (remaining <= 0) {
                    return error("http_timeout");
                }
                timeout = timeout.compareTo(Duration.ofMillis(remaining)) < 0
                        ? timeout
                        : Duration.ofMillis(remaining);
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", String.join(
                            ",", endpoint.getResponseContentTypes()));
            String credentialEnv = endpoint.getCredentialEnv();
            if (credentialEnv != null && !credentialEnv.isBlank()) {
                String credential = System.getenv(credentialEnv);
                if (credential == null || credential.isBlank()) {
                    return error("credential_unavailable");
                }
                builder.header(endpoint.getCredentialHeader(), credential);
            }
            HttpRequest httpRequest = builder
                    .method(endpoint.getMethod().toUpperCase(Locale.ROOT),
                            HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpToolExecutionState state = state(toolContext);
            HttpToolExecutionState.ResponseReservation reservation =
                    state == null
                            ? null
                            : state.reserveUpTo(endpoint.getMaxResponseBytes());
            if (reservation == null) {
                return error("http_response_budget_exhausted");
            }
            int transportLimit = Math.toIntExact(reservation.maximumBytes());
            HttpResponseData response;
            try {
                response = transport.execute(
                        httpRequest,
                        timeout,
                        transportLimit,
                        validatedAddresses);
            } catch (ResponseTooLargeException e) {
                state.commit(reservation, reservation.maximumBytes());
                return error(transportLimit < endpoint.getMaxResponseBytes()
                        ? "http_response_budget_exhausted"
                        : "response_too_large");
            } catch (java.net.http.HttpTimeoutException | TimeoutException e) {
                state.release(reservation);
                return error("http_timeout");
            } catch (InterruptedException e) {
                state.release(reservation);
                Thread.currentThread().interrupt();
                return error("http_interrupted");
            } catch (ConnectException | UnknownHostException e) {
                state.release(reservation);
                return error("http_unavailable");
            } catch (IOException e) {
                state.release(reservation);
                return error("http_failed");
            } catch (RuntimeException e) {
                state.release(reservation);
                return error("http_failed");
            }
            if (response == null) {
                state.release(reservation);
                return error("http_failed");
            }
            byte[] body = response.body() == null ? new byte[0] : response.body();
            if (body.length > transportLimit) {
                state.commit(reservation, reservation.maximumBytes());
                return error(transportLimit < endpoint.getMaxResponseBytes()
                        ? "http_response_budget_exhausted"
                        : "response_too_large");
            }
            state.commit(reservation, body.length);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return error("http_status_not_allowed");
            }
            String contentType = contentType(response.contentType());
            if (body.length > 0 && !contentTypeAllowed(contentType)) {
                return error("response_content_type_rejected");
            }
            JsonNode json = null;
            if (body.length > 0 && contentType.contains("json")) {
                try {
                    json = objectMapper.readTree(body);
                    validateJson(json, 0, new JsonLimits());
                } catch (Exception e) {
                    return error("invalid_json_response");
                }
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", response.statusCode());
            output.put("contentType", contentType);
            output.put("body", json != null
                    ? json
                    : new String(body, java.nio.charset.StandardCharsets.UTF_8));
            String serialized = serialize(output);
            if (serialized.length() > endpoint.getMaxResultCharacters()) {
                return error("http_result_budget_exhausted");
            }
            return serialized;
        }

        private JsonNode parseInput(String input) {
            try {
                JsonNode parsed = objectMapper.readTree(
                        input == null || input.isBlank() ? "{}" : input);
                if (parsed == null || !parsed.isObject()) {
                    throw new IllegalArgumentException();
                }
                Set<String> allowed = new HashSet<>();
                for (RagChatProperties.HttpQueryParameterProperties parameter
                        : endpoint.getQueryParameters()) {
                    allowed.add(parameter.getName());
                }
                parsed.fieldNames().forEachRemaining(name -> {
                    if (!allowed.contains(name)) {
                        throw new IllegalArgumentException();
                    }
                });
                return parsed;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        private ToolDefinition definition(
                RagChatProperties.HttpEndpointProperties configured) {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (RagChatProperties.HttpQueryParameterProperties parameter
                    : configured.getQueryParameters()) {
                properties.put(
                        parameter.getName(),
                        Map.of(
                                "type", "string",
                                "maxLength", parameter.getMaxLength()));
                if (parameter.isRequired()) {
                    required.add(parameter.getName());
                }
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("additionalProperties", false);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            return ToolDefinition.builder()
                    .name(configured.getToolName())
                    .description(
                            "Call the configured read-only " + configured.getCapability()
                                    + " HTTP endpoint after loading Skill "
                                    + configured.getSkillName() + ".")
                    .inputSchema(serialize(schema))
                    .build();
        }

        private URI endpointUri(
                RagChatProperties.HttpEndpointProperties configured,
                Map<String, String> query) {
            StringBuilder value = new StringBuilder(configured.getBaseUrl());
            String path = configured.getPath();
            if (!value.toString().endsWith("/") && !path.startsWith("/")) {
                value.append('/');
            }
            if (value.toString().endsWith("/") && path.startsWith("/")) {
                value.append(path.substring(1));
            } else {
                value.append(path);
            }
            if (!query.isEmpty()) {
                value.append('?');
                boolean first = true;
                for (Map.Entry<String, String> entry : query.entrySet()) {
                    if (!first) {
                        value.append('&');
                    }
                    first = false;
                    value.append(encode(entry.getKey()))
                            .append('=')
                            .append(encode(entry.getValue()));
                }
            }
            URI uri = URI.create(value.toString());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri;
        }

        private String encode(String value) {
            return java.net.URLEncoder.encode(
                            value,
                            java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
        }

        private InetAddress[] resolvePublicTarget(String host) {
            if (host == null || host.isBlank()) {
                return null;
            }
            final InetAddress[] addresses;
            try {
                addresses = addressResolver.resolve(host);
            } catch (UnknownHostException e) {
                return null;
            }
            if (addresses == null || addresses.length == 0) {
                return null;
            }
            for (InetAddress address : addresses) {
                if (!publicAddress(address)) {
                    return null;
                }
            }
            return Arrays.copyOf(addresses, addresses.length);
        }

        private boolean publicAddress(InetAddress address) {
            if (address == null || address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return false;
            }
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                int first = bytes[0] & 0xff;
                int second = bytes[1] & 0xff;
                int third = bytes[2] & 0xff;
                if (first == 0 || first == 10 || first == 127
                        || first >= 224
                        || first == 169 && second == 254
                        || first == 172 && second >= 16 && second <= 31
                        || first == 192 && second == 168
                        || first == 100 && second >= 64 && second <= 127
                        || first == 192 && second == 0 && third == 0
                        || first == 192 && second == 0 && third == 2
                        || first == 192 && second == 88 && third == 99
                        || first == 198 && (second == 18 || second == 19)
                        || first == 198 && second == 51 && third == 100
                        || first == 203 && second == 0 && third == 113) {
                    return false;
                }
            } else if (bytes.length == 16) {
                int first = bytes[0] & 0xff;
                if (first == 0xfc || first == 0xfd
                        || first == 0xff
                        || first == 0xfe && (bytes[1] & 0xc0) == 0x80) {
                    return false;
                }
                if (first == 0x20 && (bytes[1] & 0xff) == 0x01
                        && (bytes[2] & 0xff) == 0x0d
                        && (bytes[3] & 0xff) == 0xb8) {
                    return false;
                }
                boolean ipv4Embedded = true;
                for (int index = 0; index < 10; index++) {
                    ipv4Embedded &= bytes[index] == 0;
                }
                ipv4Embedded &= (bytes[10] == 0 && bytes[11] == 0)
                        || (bytes[10] == (byte) 0xff
                        && bytes[11] == (byte) 0xff);
                if (ipv4Embedded) {
                    try {
                        return publicAddress(
                                InetAddress.getByAddress(
                                        new byte[] {
                                                bytes[12], bytes[13],
                                                bytes[14], bytes[15]}));
                    } catch (UnknownHostException e) {
                        return false;
                    }
                }
                if ((first & 0xe0) != 0x20
                        || hasPrefix(bytes, 23, 0x20, 0x01, 0x00)
                        || hasPrefix(bytes, 16, 0x20, 0x02)
                        || hasPrefix(bytes, 32, 0x20, 0x01, 0x0d, 0xb8)
                        || hasPrefix(bytes, 20, 0x3f, 0xff, 0x00)) {
                    return false;
                }
            }
            return true;
        }

        private boolean hasPrefix(
                byte[] address,
                int prefixBits,
                int... prefixBytes) {
            int wholeBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            if (address.length * 8 < prefixBits
                    || prefixBytes.length < wholeBytes
                    + (remainingBits == 0 ? 0 : 1)) {
                return false;
            }
            for (int index = 0; index < wholeBytes; index++) {
                if ((address[index] & 0xff) != prefixBytes[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainingBits) & 0xff;
            return ((address[wholeBytes] & 0xff) & mask)
                    == (prefixBytes[wholeBytes] & mask);
        }

        private String contentType(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        }

        private boolean contentTypeAllowed(String value) {
            return endpoint.getResponseContentTypes().stream()
                    .map(item -> item.toLowerCase(Locale.ROOT))
                    .anyMatch(item -> item.equals(value));
        }

        private void validateJson(
                JsonNode node,
                int depth,
                JsonLimits limits) {
            if (node == null || depth > endpoint.getMaxJsonDepth()
                    || ++limits.nodes > endpoint.getMaxJsonNodes()) {
                throw new IllegalArgumentException();
            }
            if (node.isArray()) {
                if (node.size() > endpoint.getMaxJsonArrayItems()) {
                    throw new IllegalArgumentException();
                }
                for (JsonNode child : node) {
                    validateJson(child, depth + 1, limits);
                }
            } else if (node.isObject()) {
                for (JsonNode child : node) {
                    validateJson(child, depth + 1, limits);
                }
            }
        }

        private RuntimeSkillLoadSession skillSession(ToolContext context) {
            if (context == null || context.getContext() == null) {
                return null;
            }
            Object value = context.getContext().get(
                    RuntimeSkillLoadSession.CONTEXT_KEY);
            return value instanceof RuntimeSkillLoadSession session
                    ? session : null;
        }

        private RagChatToolRequestContext request(ToolContext context) {
            if (context == null || context.getContext() == null
                    || !(context.getContext().get(
                    RagChatToolContextKeys.REQUEST)
                    instanceof RagChatToolRequestContext request)) {
                throw new IllegalStateException(
                        "Missing server-owned HTTP tool request context");
            }
            return request;
        }

        private HttpToolExecutionState state(ToolContext context) {
            if (context == null || context.getContext() == null) {
                return null;
            }
            Object value = context.getContext().get(
                    HttpToolExecutionState.CONTEXT_KEY);
            return value instanceof HttpToolExecutionState state
                    ? state : null;
        }

        private String error(String code) {
            return serialize(Map.of("error", code));
        }

        private String serialize(Object value) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to serialize HTTP tool result", e);
            }
        }
    }

    private static final class JsonLimits {
        private int nodes;
    }

    @FunctionalInterface
    public interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    @FunctionalInterface
    public interface HttpTransport extends AutoCloseable {
        HttpResponseData execute(
                HttpRequest request,
                Duration timeout,
                int maxResponseBytes,
                InetAddress[] validatedAddresses)
                throws IOException, InterruptedException, TimeoutException;

        @Override
        default void close() throws Exception {
        }
    }

    public record HttpResponseData(
            int statusCode,
            String contentType,
            byte[] body) {
    }

    static final class PinnedDnsResolver implements DnsResolver {
        private final Map<String, InetAddress[]> addresses =
                new ConcurrentHashMap<>();

        void pin(String host, InetAddress[] validatedAddresses)
                throws UnknownHostException {
            if (host == null || host.isBlank()
                    || validatedAddresses == null
                    || validatedAddresses.length == 0) {
                throw new UnknownHostException("No validated address");
            }
            addresses.put(
                    host.toLowerCase(Locale.ROOT),
                    Arrays.copyOf(validatedAddresses, validatedAddresses.length));
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] found = addresses.get(
                    host == null ? "" : host.toLowerCase(Locale.ROOT));
            if (found == null || found.length == 0) {
                throw new UnknownHostException("Host is not pinned");
            }
            return Arrays.copyOf(found, found.length);
        }

        @Override
        public String resolveCanonicalHostname(String host)
                throws UnknownHostException {
            resolve(host);
            return host;
        }
    }

    private static final class PinnedDnsHttpTransport implements HttpTransport {
        private final PinnedDnsResolver dnsResolver = new PinnedDnsResolver();
        private final PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(dnsResolver)
                        .setMaxConnTotal(16)
                        .setMaxConnPerRoute(4)
                        .build();
        private final CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .build();

        @Override
        public HttpResponseData execute(
                HttpRequest request,
                Duration timeout,
                int maxResponseBytes,
                InetAddress[] validatedAddresses)
                throws IOException, TimeoutException {
            dnsResolver.pin(request.uri().getHost(), validatedAddresses);
            HttpUriRequestBase apacheRequest =
                    new HttpUriRequestBase(request.method(), request.uri());
            request.headers().map().forEach((name, values) ->
                    values.forEach(value -> apacheRequest.addHeader(
                            new BasicHeader(name, value, true))));
            Timeout configuredTimeout = Timeout.of(timeout);
            apacheRequest.setConfig(RequestConfig.custom()
                    .setRedirectsEnabled(false)
                    .setConnectionRequestTimeout(configuredTimeout)
                    .setConnectTimeout(configuredTimeout)
                    .setResponseTimeout(configuredTimeout)
                    .build());
            try {
                return client.execute(apacheRequest, response -> {
                    HttpEntity entity = response.getEntity();
                    byte[] body;
                    if (entity == null) {
                        body = new byte[0];
                    } else {
                        try (InputStream input = entity.getContent()) {
                            body = readBounded(input, maxResponseBytes);
                        }
                    }
                    org.apache.hc.core5.http.Header contentType =
                            response.getFirstHeader("Content-Type");
                    return new HttpResponseData(
                            response.getCode(),
                            contentType == null ? "" : contentType.getValue(),
                            body);
                });
            } catch (java.io.InterruptedIOException e) {
                TimeoutException timeoutException =
                        new TimeoutException("HTTP tool request timed out");
                timeoutException.initCause(e);
                throw timeoutException;
            }
        }

        private byte[] readBounded(InputStream input, int maximum)
                throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.min(maximum, 8_192));
            byte[] buffer = new byte[8_192];
            int total = 0;
            int read;
            while ((read = input.read(
                    buffer,
                    0,
                    Math.min(buffer.length, maximum - total + 1))) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maximum) {
                    throw new ResponseTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }

        @Override
        public void close() throws IOException {
            client.close();
        }
    }

    private static final class ResponseTooLargeException
            extends IOException {
    }
}
