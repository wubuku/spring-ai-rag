package com.springairag.core.resilience;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class UpstreamResponseErrorHandler implements ResponseErrorHandler {
    /** 只保留一小段错误正文用于诊断，避免把大响应或请求回显带进异常/日志。 */
    private static final int MAX_ERROR_BODY_BYTES = 1024;

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
        throw failure(response.getStatusCode(), response.getHeaders(), readBody(response));
    }

    public static ExchangeFilterFunction webClientFilter() {
        return (request, next) -> next.exchange(request).flatMap(response -> {
            if (!response.statusCode().isError()) return Mono.just(response);
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .onErrorResume(ignored -> Mono.just(""))
                    .map(body -> truncate(body.getBytes(StandardCharsets.UTF_8)))
                    .defaultIfEmpty(new byte[0])
                    .flatMap(body -> Mono.error(
                            failure(response.statusCode(), response.headers().asHttpHeaders(), body)));
        });
    }

    private static byte[] readBody(ClientHttpResponse response) throws IOException {
        try (var body = response.getBody()) {
            return body.readNBytes(MAX_ERROR_BODY_BYTES);
        }
    }

    private static byte[] truncate(byte[] body) {
        if (body.length <= MAX_ERROR_BODY_BYTES) return body;
        byte[] copy = new byte[MAX_ERROR_BODY_BYTES];
        System.arraycopy(body, 0, copy, 0, MAX_ERROR_BODY_BYTES);
        return copy;
    }

    private static RestClientResponseException failure(HttpStatusCode status, HttpHeaders source,
                                                       byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        String wait = source.getFirst("Retry-After");
        if (wait != null && wait.length() <= 128) headers.set("Retry-After", wait);
        if (status.is5xxServerError()) {
            return HttpServerErrorException.create(status, "Upstream service failed",
                    headers, body, StandardCharsets.UTF_8);
        }
        return HttpClientErrorException.create(status, "Upstream request rejected",
                headers, body, StandardCharsets.UTF_8);
    }
}
