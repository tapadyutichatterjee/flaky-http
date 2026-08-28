package com.tapadyuti.flakyhttp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/**
 * A mock implementation of {@link HttpResponse} used by {@link FlakyHttpClient}
 * to simulate artificial failures without performing actual network I/O.
 *
 * @param <T> The type of the response body.
 */
public class MockHttpResponse<T> implements HttpResponse<T> {
    private final int statusCode;
    private final T body;
    private final HttpRequest request;
    private final HttpClient.Version version;
    private final HttpHeaders headers;

    /**
     * Creates a new MockHttpResponse.
     *
     * @param statusCode The HTTP status code to return.
     * @param body       The response body.
     * @param request    The original request that triggered this response.
     */
    public MockHttpResponse(int statusCode, T body, HttpRequest request) {
        this(statusCode, body, request, HttpClient.Version.HTTP_1_1);
    }

    public MockHttpResponse(int statusCode, T body, HttpRequest request, HttpClient.Version version) {
        this(statusCode, body, request, version, HttpHeaders.of(Map.of(), (a, b) -> true));
    }

    public MockHttpResponse(int statusCode, T body, HttpRequest request, HttpClient.Version version,
                            HttpHeaders headers) {
        this.statusCode = statusCode;
        this.body = body;
        this.request = Objects.requireNonNull(request, "request");
        this.version = Objects.requireNonNull(version, "version");
        this.headers = Objects.requireNonNull(headers, "headers");
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    public T body() {
        return body;
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public URI uri() {
        return request.uri();
    }

    @Override
    public Optional<javax.net.ssl.SSLSession> sslSession() {
        return Optional.empty();
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpClient.Version version() {
        return version;
    }
}
