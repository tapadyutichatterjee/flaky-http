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
 * Immutable in-memory {@link HttpResponse} used to represent a synthetic result
 * without network I/O.
 *
 * <p>{@link FlakyHttpClient} creates instances whose body has been produced by
 * the caller's body handler. The public constructors are also useful for tests
 * that need a minimal response object. This implementation has no previous
 * response or SSL session, and its URI is always derived from the original
 * request.</p>
 *
 * <p>The response is immutable and safe to share between threads when its body
 * object is itself safe to share. The request and {@link HttpHeaders} types are
 * immutable.</p>
 *
 * @param <T> the response body type
 * @see FlakyHttpClient
 */
public final class MockHttpResponse<T> implements HttpResponse<T> {
    private final int statusCode;
    private final T body;
    private final HttpRequest request;
    private final HttpClient.Version version;
    private final HttpHeaders headers;

    /**
     * Creates a response with empty headers and HTTP/1.1 metadata.
     *
     * @param statusCode the status code reported by {@link #statusCode()}
     * @param body the body returned by {@link #body()}, which may be {@code null}
     * @param request the non-null request associated with the response
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public MockHttpResponse(int statusCode, T body, HttpRequest request) {
        this(statusCode, body, request, HttpClient.Version.HTTP_1_1);
    }

    /**
     * Creates a response with empty headers and explicit HTTP version metadata.
     *
     * @param statusCode the status code reported by {@link #statusCode()}
     * @param body the body returned by {@link #body()}, which may be {@code null}
     * @param request the non-null request associated with the response
     * @param version the non-null HTTP version reported by {@link #version()}
     * @throws NullPointerException if {@code request} or {@code version} is null
     */
    public MockHttpResponse(int statusCode, T body, HttpRequest request, HttpClient.Version version) {
        this(statusCode, body, request, version, HttpHeaders.of(Map.of(), (a, b) -> true));
    }

    /**
     * Creates a response with explicit version and header metadata.
     *
     * <p>No validation is performed on {@code statusCode}; callers constructing
     * responses directly are responsible for choosing an appropriate value.</p>
     *
     * @param statusCode the status code reported by {@link #statusCode()}
     * @param body the body returned by {@link #body()}, which may be {@code null}
     * @param request the non-null request associated with the response
     * @param version the non-null HTTP version reported by {@link #version()}
     * @param headers the non-null immutable header collection to expose
     * @throws NullPointerException if {@code request}, {@code version}, or
     *                              {@code headers} is null
     */
    public MockHttpResponse(int statusCode, T body, HttpRequest request, HttpClient.Version version,
                            HttpHeaders headers) {
        this.statusCode = statusCode;
        this.body = body;
        this.request = Objects.requireNonNull(request, "request");
        this.version = Objects.requireNonNull(version, "version");
        this.headers = Objects.requireNonNull(headers, "headers");
    }

    /**
     * Returns the configured response status.
     *
     * @return the response status code
     */
    @Override
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the configured immutable response headers.
     *
     * @return the response headers, never {@code null}
     */
    @Override
    public HttpHeaders headers() {
        return headers;
    }

    /**
     * Returns the configured body without further conversion.
     *
     * @return the response body, possibly {@code null}
     */
    @Override
    public T body() {
        return body;
    }

    /**
     * Returns the original request associated with this response.
     *
     * @return the original request, never {@code null}
     */
    @Override
    public HttpRequest request() {
        return request;
    }

    /**
     * Returns the URI of the original request.
     *
     * @return {@code request().uri()}
     */
    @Override
    public URI uri() {
        return request.uri();
    }

    /**
     * Returns no SSL session because a synthetic response has no connection.
     *
     * @return an empty optional
     */
    @Override
    public Optional<javax.net.ssl.SSLSession> sslSession() {
        return Optional.empty();
    }

    /**
     * Returns no previous response because this object does not model redirects.
     *
     * @return an empty optional
     */
    @Override
    public Optional<HttpResponse<T>> previousResponse() {
        return Optional.empty();
    }

    /**
     * Returns the HTTP version metadata supplied at construction time.
     *
     * @return the configured HTTP version, never {@code null}
     */
    @Override
    public HttpClient.Version version() {
        return version;
    }
}
