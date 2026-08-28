package com.tapadyuti.flakyhttp;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

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

    /**
     * Creates a new MockHttpResponse.
     *
     * @param statusCode The HTTP status code to return.
     * @param body       The response body.
     * @param request    The original request that triggered this response.
     */
    public MockHttpResponse(int statusCode, T body, HttpRequest request) {
        this.statusCode = statusCode;
        this.body = body;
        this.request = request;
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public Optional<HttpHeaders> headers() {
        return Optional.empty();
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
    public long contentLength() {
        if (body == null) {
            return 0;
        }
        if (body instanceof String) {
            return ((String) body).length();
        }
        return -1; // Unknown content length for non-string bodies
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpRequest.Version version() {
        return HttpRequest.Version.HTTP_2;
    }
}
