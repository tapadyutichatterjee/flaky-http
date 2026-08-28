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
 * Composition-based wrapper around {@link HttpClient} that injects artificial
 * latency and synthetic HTTP error responses.
 *
 * <p>For a targeted request, the client performs these operations in order:</p>
 * <ol>
 *   <li>obtain and apply the configured artificial latency;</li>
 *   <li>make an independent random failure decision;</li>
 *   <li>return a synthetic response on failure, or delegate the real request.</li>
 * </ol>
 *
 * <p>A synthetic failure does not perform network I/O. Its empty body is
 * converted by the supplied {@link HttpResponse.BodyHandler}, its status comes
 * from {@link FlakyConfig#getErrorStatus()}, and its request, URI, and preferred
 * HTTP version reflect the original request and delegate. See
 * {@link MockHttpResponse} for the remaining response metadata.</p>
 *
 * <p>This type deliberately does not extend {@code HttpClient}; callers must use
 * it directly or place both clients behind an application-owned abstraction.
 * Instances may be used concurrently provided that the delegate, configured
 * latency strategy, supplied scheduler, and body handlers support the same use.</p>
 *
 * <p>The two-argument constructor owns an internal daemon scheduler and should
 * be used with try-with-resources. The three-argument constructor borrows its
 * scheduler and never shuts it down.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * FlakyConfig config = FlakyConfig.builder()
 *         .failureRate(1.0)
 *         .errorStatus(503)
 *         .build();
 *
 * try (FlakyHttpClient client =
 *              new FlakyHttpClient(HttpClient.newHttpClient(), config)) {
 *     HttpResponse<String> response =
 *             client.send(request, HttpResponse.BodyHandlers.ofString());
 * }
 * }</pre>
 *
 * @see FlakyConfig
 * @see LatencyStrategy
 * @see MockHttpResponse
 */
public final class FlakyHttpClient implements AutoCloseable {
    private final HttpClient delegate;
    private final FlakyConfig config;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    /**
     * Creates a client that uses a caller-owned scheduler for asynchronous
     * artificial delays.
     *
     * <p>Calling {@link #close()} or {@link #shutdown()} does not shut down the
     * supplied scheduler. Its lifecycle remains the caller's responsibility.</p>
     *
     * @param delegate the real HTTP client used for non-failing requests
     * @param config the immutable injection configuration
     * @param scheduler the caller-owned scheduler used for asynchronous delays
     * @throws NullPointerException if any argument is {@code null}
     */
    public FlakyHttpClient(HttpClient delegate, FlakyConfig config, ScheduledExecutorService scheduler) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = false;
    }

    /**
     * Creates a client with an internally owned single-threaded daemon scheduler.
     * The scheduler thread is named {@code flaky-http-scheduler} and is used only
     * to begin asynchronous work after an artificial delay.
     *
     * @param delegate the real HTTP client used for non-failing requests
     * @param config the immutable injection configuration
     * @throws NullPointerException if either argument is {@code null}
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
     * Sends a request synchronously, applying configured injection behavior when
     * the request URI is targeted.
     *
     * <p>Artificial latency blocks the calling thread and is outside the
     * delegate's request timeout. If the delay is interrupted, this method
     * propagates {@link InterruptedException} and does not delegate the request.
     * When a synthetic failure is selected, the body handler consumes an empty
     * body and the delegate is not invoked.</p>
     *
     * @param request the request to send
     * @param responseBodyHandler the handler used for a real or synthetic body
     * @param <T> the response body type produced by the handler
     * @return the synthetic response or the delegate's response
     * @throws IOException if the delegated request encounters an I/O error
     * @throws InterruptedException if artificial latency or delegation is interrupted
     * @throws CompletionException if processing a synthetic body completes exceptionally
     * @throws NullPointerException if an argument is {@code null}
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
     * Sends a request asynchronously, applying configured injection behavior
     * without blocking the calling thread.
     *
     * <p>For targeted requests, the configured delay is scheduled first. After
     * the delay, the future is completed with a synthetic response or linked to
     * {@link HttpClient#sendAsync(HttpRequest, HttpResponse.BodyHandler)} on the
     * delegate. Cancelling the returned future cancels a pending scheduled task
     * and attempts to cancel delegate work that has already begun.</p>
     *
     * <p>For non-targeted requests, this method returns the delegate's future
     * directly. Consequently, its exact cancellation and exception behavior is
     * the delegate's behavior.</p>
     *
     * @param request the request to send
     * @param responseBodyHandler the handler used for a real or synthetic body
     * @param <T> the response body type produced by the handler
     * @return a future for the synthetic or delegated response
     * @throws NullPointerException if an argument is {@code null}
     * @throws java.util.concurrent.RejectedExecutionException if a targeted
     *         request cannot be scheduled, including after an owned scheduler
     *         has been shut down
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
     * Initiates an orderly shutdown of the internally owned scheduler.
     *
     * <p>This method is idempotent. It has no effect when the client was created
     * with a caller-owned scheduler. Tasks that have already been scheduled are
     * allowed to execute according to {@link ScheduledExecutorService#shutdown()}.
     * Subsequent targeted asynchronous requests on a client whose internal
     * scheduler has been shut down may be rejected.</p>
     *
     * @see #close()
     */
    public void shutdown() {
        if (ownsScheduler) {
            scheduler.shutdown();
        }
    }

    /**
     * Releases scheduler resources owned by this client.
     *
     * <p>This method is equivalent to {@link #shutdown()} and does not close the
     * wrapped {@link HttpClient} or a caller-supplied scheduler.</p>
     */
    @Override
    public void close() {
        shutdown();
    }
}
