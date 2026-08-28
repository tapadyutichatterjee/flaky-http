package com.tapadyuti.flakyhttp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A wrapper around {@link HttpClient} that injects artificial failures and latency
 * based on the provided {@link FlakyConfig}.
 *
 * <p>This class uses the Composition pattern to delegate actual network calls to a real {@link HttpClient}
 * while applying chaos logic before the request is executed.</p>
 */
public class FlakyHttpClient {
    private final HttpClient delegate;
    private final FlakyConfig config;
    private final ScheduledExecutorService scheduler;

    /**
     * Creates a new FlakyHttpClient.
     *
     * @param delegate The real {@link HttpClient} instance to delegate to.
     * @param config   The configuration for failure rates and latency.
     * @param scheduler An executor service used to handle asynchronous delays.
     *                  If null, a default single-threaded scheduled executor will be used.
     */
    public FlakyHttpClient(HttpClient delegate, FlakyConfig config, ScheduledExecutorService scheduler) {
        this.delegate = delegate;
        this.config = config;
        this.scheduler = scheduler;
    }

    /**
     * Overloaded constructor that creates a default scheduler if none is provided.
     *
     * @param delegate The real {@link HttpClient} instance to delegate to.
     * @param config   The configuration for failure rates and latency.
     */
    public FlakyHttpClient(HttpClient delegate, FlakyConfig config) {
        this(delegate, config, java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flaky-http-scheduler");
            t.setDaemon(true);
            return t;
        }));
    }

    /**
     * Sends a synchronous request.
     *
     * @param request                The request to send.
     * @param responseBodyHandler    The handler to use to process the response body.
     * @param <T>                    The type of the response body.
     * @return The response.
     * @throws IOException              If an I/O error occurs.
     * @throws InterruptedException     If the operation is interrupted.
     * @throws CompletionException      If the request fails.
     */
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {

        if (shouldInjectChaos(request)) {
            applyLatency();
            if (shouldFail()) {
                return createMockResponse(request);
            }
        }

        return delegate.send(request, responseBodyHandler);
    }

    /**
     * Sends an asynchronous request.
     *
     * @param request            The request to send.
     * @param responseBodyHandler The handler to use to process the response body.
     * @param <T>                The type of the response body.
     * @return A {@link CompletableFuture} that will be completed when the response is received.
     */
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        if (shouldInjectChaos(request)) {
            long delay = config.getLatencyStrategy() != null ? config.getLatencyStrategy().getDelayMillis() : 0;

            CompletableFuture<HttpResponse<T>> future = new CompletableFuture<>();

            scheduler.schedule(() -> {
                if (shouldFail()) {
                    future.complete(createMockResponse(request));
                } else {
                    delegate.sendAsync(request, responseBodyHandler)
                            .thenAccept(future::complete)
                            .exceptionally(ex -> {
                                future.completeExceptionally(ex);
                                return null;
                            });
                }
            }, delay, TimeUnit.MILLISECONDS);

            return future;
        }

        return delegate.sendAsync(request, responseBodyHandler);
    }

    private boolean shouldInjectChaos(HttpRequest request) {
        if (config.getTargetUrlPattern() == null) {
            return true;
        }
        return config.getTargetUrlPattern().matcher(request.uri().toString()).matches();
    }

    private void applyLatency() {
        if (config.getLatencyStrategy() == null) {
            return;
        }
        long delay = config.getLatencyStrategy().getDelayMillis();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean shouldFail() {
        return ThreadLocalRandom.current().nextDouble() < config.getFailureRate();
    }

    @SuppressWarnings("unchecked")
    private <T> HttpResponse<T> createMockResponse(HttpRequest request) {
        // Since we are mocking a failure (e.g. 500), we assume the body is empty or a simple string.
        // The actual type T is determined by the BodyHandler, but for a mock, we return a MockHttpResponse
        // with a null or empty body, as most resilience tests only care about the status code.
        return new MockHttpResponse<>(config.getErrorStatus(), null, request);
    }

    /**
     * Shuts down the internal scheduler.
     */
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
