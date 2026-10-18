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
    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
        throw failure(response.getStatusCode(), response.getHeaders());
    }

    public static ExchangeFilterFunction webClientFilter() {
        return (request, next) -> next.exchange(request).flatMap(response -> {
            if (!response.statusCode().isError()) return Mono.just(response);
            var error = failure(response.statusCode(), response.headers().asHttpHeaders());
            return response.releaseBody().onErrorResume(ignored -> Mono.empty()).then(Mono.error(error));
        });
    }

    private static RestClientResponseException failure(HttpStatusCode status, HttpHeaders source) {
        HttpHeaders headers = new HttpHeaders();
        String wait = source.getFirst("Retry-After");
        if (wait != null && wait.length() <= 128) headers.set("Retry-After", wait);
        if (status.is5xxServerError()) {
            return HttpServerErrorException.create(status, "Upstream service failed",
                    headers, new byte[0], StandardCharsets.UTF_8);
        }
        return HttpClientErrorException.create(status, "Upstream request rejected",
                headers, new byte[0], StandardCharsets.UTF_8);
    }
}
