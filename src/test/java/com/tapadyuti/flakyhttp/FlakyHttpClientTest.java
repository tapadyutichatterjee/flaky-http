package com.tapadyuti.flakyhttp;

import org.junit.jupiter.api.Test;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FlakyHttpClientTest {
    private final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.example.com/test")).GET().build();

    @Test void injectsLatencyBeforeDelegating() throws Exception {
        RecordingClient delegate = new RecordingClient();
        try (FlakyHttpClient client = new FlakyHttpClient(delegate, FlakyConfig.builder()
                .latency(LatencyStrategy.fixed(50)).build())) {
            long start = System.nanoTime();
            client.send(request, HttpResponse.BodyHandlers.ofString());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 45);
            assertEquals(1, delegate.calls.get());
        }
    }

    @Test void syntheticFailureUsesConfiguredStatusAndBodyHandler() throws Exception {
        RecordingClient delegate = new RecordingClient();
        try (FlakyHttpClient client = new FlakyHttpClient(delegate, FlakyConfig.builder()
                .failureRate(1).errorStatus(429).build())) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(429, response.statusCode());
            assertEquals("", response.body());
            assertEquals("0", response.headers().firstValue("content-length").orElseThrow());
            assertEquals(0, delegate.calls.get());
        }
    }

    @Test void nonTargetedUrlDelegatesWithoutChaos() throws Exception {
        RecordingClient delegate = new RecordingClient();
        try (FlakyHttpClient client = new FlakyHttpClient(delegate, FlakyConfig.builder()
                .failureRate(1).targetUrls(".*google\\.com.*").build())) {
            assertSame(delegate.response, client.send(request, HttpResponse.BodyHandlers.ofString()));
            assertEquals(1, delegate.calls.get());
        }
    }

    @Test void asyncFailureIsDelayedAndDoesNotDelegate() throws Exception {
        RecordingClient delegate = new RecordingClient();
        try (FlakyHttpClient client = new FlakyHttpClient(delegate, FlakyConfig.builder()
                .failureRate(1).latency(LatencyStrategy.fixed(30)).build())) {
            CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            assertFalse(future.isDone());
            assertEquals(500, future.get(1, TimeUnit.SECONDS).statusCode());
            assertEquals(0, delegate.calls.get());
        }
    }

    @Test void asyncCancellationPreventsDelayedDelegation() throws Exception {
        RecordingClient delegate = new RecordingClient();
        try (FlakyHttpClient client = new FlakyHttpClient(delegate, FlakyConfig.builder()
                .latency(LatencyStrategy.fixed(150)).build())) {
            CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            assertTrue(future.cancel(true));
            Thread.sleep(200);
            assertEquals(0, delegate.calls.get());
        }
    }

    @Test void interruptionAbortsBeforeDelegation() throws Exception {
        RecordingClient delegate = new RecordingClient();
        try (FlakyHttpClient client = new FlakyHttpClient(delegate, FlakyConfig.builder()
                .latency(LatencyStrategy.fixed(1_000)).build())) {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> client.send(request, HttpResponse.BodyHandlers.ofString()));
            assertEquals(0, delegate.calls.get());
        } finally { Thread.interrupted(); }
    }

    @Test void validatesConfigurationBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> FlakyConfig.builder().failureRate(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> FlakyConfig.builder().errorStatus(200));
        assertThrows(NullPointerException.class, () -> FlakyConfig.builder().latency(null));
        assertThrows(IllegalArgumentException.class, () -> LatencyStrategy.fixed(-1));
        assertThrows(IllegalArgumentException.class, () -> LatencyStrategy.random(-1, 2));
        assertEquals(Long.MAX_VALUE, LatencyStrategy.random(Long.MAX_VALUE, Long.MAX_VALUE).getDelayMillis());
    }

    private static final class RecordingClient extends HttpClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final HttpResponse<String> response = new MockHttpResponse<>(200, "ok",
                HttpRequest.newBuilder(URI.create("https://api.example.com/test")).build());
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { return defaultSslContext(); }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            calls.incrementAndGet();
            return (HttpResponse<T>) response;
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            try { return CompletableFuture.completedFuture(send(request, handler)); }
            catch (Exception e) { return failedFuture(e); }
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }
        private static SSLContext defaultSslContext() {
            try { return SSLContext.getDefault(); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        private static <T> CompletableFuture<T> failedFuture(Throwable error) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(error);
            return future;
        }
    }
}
