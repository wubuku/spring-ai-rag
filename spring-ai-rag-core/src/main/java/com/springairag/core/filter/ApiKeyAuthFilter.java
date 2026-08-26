package com.springairag.core.filter;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.openai.OpenAiErrorResponse;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.security.ApiCapabilitySupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * API Key authentication filter
 *
 * <p>Checks whether the X-API-Key request header matches:
 * <ol>
 *   <li>A valid database-stored API key (new path, checked first)</li>
 *   <li>The configured static API key (legacy path, backward compatible)</li>
 * </ol>
 *
 * <p>Excluded paths (no authentication required):
 * <ul>
 *   <li>/actuator/** — health checks</li>
 *   <li>/swagger-ui/** — API documentation</li>
 *   <li>/v3/api-docs — OpenAPI specification</li>
 *   <li>/health — health check</li>
 *   <li>/error — Spring error page</li>
 * </ul>
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Request attribute: stable principal identity after successful auth. */
    public static final String AUTHENTICATED_KEY_ATTRIBUTE = "authenticatedApiKey";

    public static final String AUTHENTICATED_CREDENTIAL_ID_ATTRIBUTE =
            "authenticatedApiCredentialId";

    public static final String AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE =
            "authenticatedApiPrincipal";

    /**
     * 旧扩展兼容名称。生产过滤链不再设置 JPA entity。
     */
    @Deprecated
    public static final String AUTHENTICATED_API_KEY_ENTITY = "authenticatedApiKeyEntity";
    public static final String AUTHENTICATED_PRINCIPAL_TYPE = "authenticatedPrincipalType";
    public static final String ROOT_AUTHENTICATED_ATTRIBUTE = "environmentRootAuthenticated";
    public static final String ROOT_MODE_ACTIVE_ATTRIBUTE = "environmentRootModeActive";

    public static final String PRINCIPAL_ENVIRONMENT_ROOT = "ENVIRONMENT_ROOT";
    public static final String PRINCIPAL_DATABASE_API_KEY = "DATABASE_API_KEY";
    public static final String PRINCIPAL_LEGACY_STATIC = "LEGACY_STATIC";

    private final String configuredApiKey;
    private final boolean authEnabled;
    private final ApiKeyManagementService apiKeyService;
    private final EnvironmentRootCredentialResolver rootCredentialResolver;

    /**
     * @param configuredApiKey legacy static API key from configuration (may be blank)
     * @param authEnabled      whether authentication is enabled
     * @param apiKeyService    optional API key management service (null means database keys unavailable)
     */
    public ApiKeyAuthFilter(String configuredApiKey, boolean authEnabled,
                            ApiKeyManagementService apiKeyService,
                            EnvironmentRootCredentialResolver rootCredentialResolver) {
        this.configuredApiKey = configuredApiKey;
        this.authEnabled = authEnabled;
        this.apiKeyService = apiKeyService;
        this.rootCredentialResolver = rootCredentialResolver != null
                ? rootCredentialResolver
                : new EnvironmentRootCredentialResolver("");
    }

    public ApiKeyAuthFilter(String configuredApiKey, boolean authEnabled,
                            ApiKeyManagementService apiKeyService) {
        this(configuredApiKey, authEnabled, apiKeyService,
                new EnvironmentRootCredentialResolver(""));
    }

    /** Backward-compatible constructor (no database key service). */
    public ApiKeyAuthFilter(String configuredApiKey, boolean authEnabled) {
        this(configuredApiKey, authEnabled, null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean rootMode = rootCredentialResolver.isConfigured();
        request.setAttribute(ROOT_MODE_ACTIVE_ATTRIBUTE, rootMode);

        // 有效 root 配置自动启用 /api/** 鉴权；未配置时保留 legacy 开关语义。
        if (!rootMode && !authEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (isExcludedPath(path, rootMode)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (rootMode && request.getParameterMap().containsKey("apiKey")) {
            log.warn("Query API credential rejected in root mode: {} {}",
                    request.getMethod(), path);
            sendUnauthorized(response, path,
                    "Query-string API credentials are not accepted. Use Authorization: Bearer or X-API-Key.");
            return;
        }

        CredentialResult credentialResult = resolveCredential(request, rootMode);
        if (credentialResult.error() != null) {
            sendUnauthorized(response, path, credentialResult.error());
            return;
        }
        String requestApiKey = credentialResult.credential();

        if (requestApiKey == null || requestApiKey.isBlank()) {
            log.warn("API Key missing: {} {}", request.getMethod(), path);
            sendUnauthorized(response, path, rootMode
                    ? "Missing API Key. Provide Authorization: Bearer or X-API-Key header."
                    : "Missing API Key. Provide X-API-Key header or ?apiKey= query parameter.");
            return;
        }

        if (rootMode && rootCredentialResolver.matches(requestApiKey)) {
            log.debug("API Key validated (environment root): {} {}",
                    request.getMethod(), path);
            request.setAttribute(AUTHENTICATED_KEY_ATTRIBUTE,
                    EnvironmentRootCredentialResolver.PRINCIPAL_ID);
            request.setAttribute(AUTHENTICATED_PRINCIPAL_TYPE,
                    PRINCIPAL_ENVIRONMENT_ROOT);
            request.setAttribute(ROOT_AUTHENTICATED_ATTRIBUTE, true);
            filterChain.doFilter(request, response);
            return;
        }

        // 数据库 Key 在 root 模式和 legacy auth 模式下都可访问数据面。
        if (apiKeyService != null) {
            AuthenticatedApiPrincipal validatedPrincipal;
            try {
                validatedPrincipal = apiKeyService.authenticate(requestApiKey);
            } catch (DataAccessException e) {
                log.error("API credential store unavailable: {} {}",
                        request.getMethod(), path);
                sendServiceUnavailable(response, path);
                return;
            } catch (ApiCapabilitySupport.InvalidPersistedCapabilitiesException e) {
                log.error("API principal policy is invalid: {} {}", request.getMethod(), path);
                sendPolicyUnavailable(response, path);
                return;
            }
            if (validatedPrincipal != null) {
                log.debug("API credential validated (database): principalId={}, version={}, {} {}",
                        validatedPrincipal.getPrincipalId(),
                        validatedPrincipal.getCredentialVersion(),
                        request.getMethod(), path);
                request.setAttribute(AUTHENTICATED_KEY_ATTRIBUTE,
                        validatedPrincipal.getPrincipalId());
                request.setAttribute(AUTHENTICATED_CREDENTIAL_ID_ATTRIBUTE,
                        validatedPrincipal.getCredentialId());
                request.setAttribute(AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                        validatedPrincipal);
                request.setAttribute(AUTHENTICATED_PRINCIPAL_TYPE,
                        PRINCIPAL_DATABASE_API_KEY);
                filterChain.doFilter(request, response);
                return;
            }
        }

        // root 模式明确禁用 legacy static fallback，避免普通静态 Key 获得 root 语义。
        if (!rootMode && configuredApiKey != null && !configuredApiKey.isBlank()
                && constantTimeEquals(configuredApiKey, requestApiKey)) {
            log.debug("API Key validated (legacy): {} {}", request.getMethod(), path);
            request.setAttribute(AUTHENTICATED_KEY_ATTRIBUTE, "legacy-static");
            request.setAttribute(AUTHENTICATED_PRINCIPAL_TYPE,
                    PRINCIPAL_LEGACY_STATIC);
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("API Key invalid: {} {}", request.getMethod(), path);
        sendUnauthorized(response, path, "Invalid API Key.");
    }

    private CredentialResult resolveCredential(HttpServletRequest request, boolean rootMode) {
        String apiKeyHeader = normalize(request.getHeader(API_KEY_HEADER));
        String authorization = normalize(request.getHeader(AUTHORIZATION_HEADER));
        String bearer = null;
        if (authorization != null) {
            if (!authorization.startsWith(BEARER_PREFIX)
                    || authorization.length() == BEARER_PREFIX.length()) {
                return new CredentialResult(null,
                        "Authorization header must use the Bearer scheme.");
            }
            bearer = normalize(authorization.substring(BEARER_PREFIX.length()));
            if (bearer == null) {
                return new CredentialResult(null,
                        "Authorization Bearer credential must not be blank.");
            }
        }
        if (apiKeyHeader != null && bearer != null
                && !constantTimeEquals(apiKeyHeader, bearer)) {
            return new CredentialResult(null,
                    "Conflicting API credentials were provided.");
        }
        if (apiKeyHeader != null) {
            return new CredentialResult(apiKeyHeader, null);
        }
        if (bearer != null) {
            return new CredentialResult(bearer, null);
        }
        if (!rootMode) {
            return new CredentialResult(normalize(request.getParameter("apiKey")), null);
        }
        return new CredentialResult(null, null);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isExcludedPath(String path, boolean rootMode) {
        return path.startsWith("/actuator") ||
                path.startsWith("/swagger-ui") ||
                path.startsWith("/v3/api-docs") ||
                path.startsWith("/health") ||
                (!rootMode && path.equals("/api/v1/rag/cache/stats")) ||
                path.startsWith("/error");
    }

    private void sendUnauthorized(
            HttpServletResponse response,
            String path,
            String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (path != null && path.startsWith("/v1/")) {
            response.getWriter().write(objectMapper.writeValueAsString(
                    OpenAiErrorResponse.of(
                            message,
                            "authentication_error",
                            null,
                            "invalid_api_key")));
            return;
        }
        ErrorResponse errorResponse = ErrorResponse.builder()
                .error("UNAUTHORIZED")
                .message(message)
                .build();
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }

    private void sendServiceUnavailable(
            HttpServletResponse response,
            String path) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (path != null && path.startsWith("/v1/")) {
            response.getWriter().write(objectMapper.writeValueAsString(
                    OpenAiErrorResponse.of(
                            "API credential service is unavailable.",
                            "server_error",
                            null,
                            "credential_service_unavailable")));
            return;
        }
        ErrorResponse errorResponse = ErrorResponse.builder()
                .error("CREDENTIAL_SERVICE_UNAVAILABLE")
                .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .message("API credential service is unavailable.")
                .build();
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }

    private void sendPolicyUnavailable(
            HttpServletResponse response,
            String path) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (path != null && path.startsWith("/v1/")) {
            response.getWriter().write(objectMapper.writeValueAsString(
                    OpenAiErrorResponse.of(
                            "API principal policy is unavailable.",
                            "server_error",
                            null,
                            "policy_service_unavailable")));
            return;
        }
        ErrorResponse errorResponse = ErrorResponse.builder()
                .error("POLICY_SERVICE_UNAVAILABLE")
                .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .message("API principal policy is unavailable.")
                .path(path)
                .build();
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }

    private record CredentialResult(String credential, String error) {
    }
}
