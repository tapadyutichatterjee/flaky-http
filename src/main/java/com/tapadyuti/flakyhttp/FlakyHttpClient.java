package com.tapadyuti.flakyhttp;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A wrapper around {@link HttpClient} that injects artificial failures and latency
 * based on the provided {@link FlakyConfig}.
 *
 * <p>This class uses the Composition pattern to delegate actual network calls to a real {@link HttpClient}
 * while applying chaos logic before the request is executed.</p>
 */
public final class FlakyHttpClient implements AutoCloseable {
    private final HttpClient delegate;
    private final FlakyConfig config;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    /**
     * Creates a new FlakyHttpClient.
     *
     * @param delegate The real {@link HttpClient} instance to delegate to.
     * @param config   The configuration for failure rates and latency.
     * @param scheduler An executor service used to handle asynchronous delays.
     *                  It remains owned by the caller and must not be null.
     */
    public FlakyHttpClient(HttpClient delegate, FlakyConfig config, ScheduledExecutorService scheduler) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = false;
    }

    /**
     * Overloaded constructor that creates a default scheduler if none is provided.
     *
     * @param delegate The real {@link HttpClient} instance to delegate to.
     * @param config   The configuration for failure rates and latency.
     */
    public FlakyHttpClient(HttpClient delegate, FlakyConfig config) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flaky-http-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.ownsScheduler = true;
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
                return createMockResponse(request, responseBodyHandler);
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
            AtomicReference<ScheduledFuture<?>> scheduledTask = new AtomicReference<>();
            AtomicReference<CompletableFuture<HttpResponse<T>>> delegateFuture = new AtomicReference<>();

            ScheduledFuture<?> task = scheduler.schedule(() -> {
                if (future.isCancelled()) return;
                if (shouldFail()) {
                    try {
                        future.complete(createMockResponse(request, responseBodyHandler));
                    } catch (RuntimeException ex) {
                        future.completeExceptionally(ex);
                    }
                } else {
                    CompletableFuture<HttpResponse<T>> delegated = delegate.sendAsync(request, responseBodyHandler);
                    delegateFuture.set(delegated);
                    if (future.isCancelled()) delegated.cancel(true);
                    delegated.whenComplete((response, error) -> {
                        if (error == null) future.complete(response);
                        else future.completeExceptionally(unwrap(error));
                    });
                }
            }, delay, TimeUnit.MILLISECONDS);
            scheduledTask.set(task);
            future.whenComplete((ignored, error) -> {
                if (future.isCancelled()) {
                    ScheduledFuture<?> scheduled = scheduledTask.get();
                    if (scheduled != null) scheduled.cancel(false);
                    CompletableFuture<?> delegated = delegateFuture.get();
                    if (delegated != null) delegated.cancel(true);
                }
            });

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

    private void applyLatency() throws InterruptedException {
        if (config.getLatencyStrategy() == null) {
            return;
        }
        long delay = config.getLatencyStrategy().getDelayMillis();
        if (delay > 0) {
            Thread.sleep(delay);
        }
    }

    private boolean shouldFail() {
        return ThreadLocalRandom.current().nextDouble() < config.getFailureRate();
    }

    private <T> HttpResponse<T> createMockResponse(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        HttpResponse.ResponseInfo info = new HttpResponse.ResponseInfo() {
            public int statusCode() { return config.getErrorStatus(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of("content-length", List.of("0")), (a, b) -> true); }
            public HttpClient.Version version() { return delegate.version(); }
        };
        HttpResponse.BodySubscriber<T> subscriber = handler.apply(info);
        subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { }
        });
        subscriber.onNext(List.of(ByteBuffer.allocate(0)));
        subscriber.onComplete();
        T body = subscriber.getBody().toCompletableFuture().join();
        return new MockHttpResponse<>(config.getErrorStatus(), body, request, info.version(), info.headers());
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    /**
     * Shuts down the internal scheduler.
     */
    public void shutdown() {
        if (ownsScheduler) {
            scheduler.shutdown();
        }
    }

    @Override
    public void close() {
        shutdown();
    }
}
