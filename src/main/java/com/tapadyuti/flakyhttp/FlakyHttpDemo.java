package com.tapadyuti.flakyhttp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Executable tour of the principal {@link FlakyHttpClient} features.
 *
 * <p>The demo contains four independent scenarios:</p>
 * <ol>
 *   <li>a deterministic synthetic {@code 503} that performs no network I/O;</li>
 *   <li>a fixed-delay request that is ultimately sent to a real server;</li>
 *   <li>a URL-filtered configuration showing that non-matching requests bypass
 *       all injection; and</li>
 *   <li>multiple concurrent asynchronous requests using random latency,
 *       probabilistic failures, and a caller-owned scheduler.</li>
 * </ol>
 *
 * <p>The first scenario is completely local. The remaining scenarios send GET
 * requests to {@code https://example.com} or {@code https://www.iana.org}; they
 * therefore require outbound network access and may report network-specific
 * exceptions. Those exceptions are printed and do not prevent later scenarios
 * from running.</p>
 *
 * <p>This class is intentionally verbose and favors instructional output over
 * compactness. Production code would normally create one configuration and
 * inject a long-lived client through an application-owned HTTP abstraction.</p>
 *
 * <h2>Running the demo</h2>
 * <p>Run {@link #main(String[])} from an IDE after importing the Maven project,
 * or compile the project and launch this class with {@code target/classes} on
 * the class path.</p>
 *
 * @see FlakyHttpClient
 * @see FlakyConfig
 * @see LatencyStrategy
 */
public final class FlakyHttpDemo {
    private static final URI TARGET_URI = URI.create("https://example.com");
    private static final URI NON_TARGET_URI = URI.create("https://www.iana.org");
    private static final String TARGET_PATTERN = "https://example\\.com(?:/.*)?";

    /**
     * Prevents construction of this utility class.
     */
    private FlakyHttpDemo() {
    }

    /**
     * Runs every demonstration scenario in a fixed order.
     *
     * <p>No command-line options are currently supported. Each scenario owns
     * and closes the resources it creates. A failure in a real network request
     * is reported by that scenario rather than propagated from this method.</p>
     *
     * @param args command-line arguments; currently ignored
     */
    public static void main(String[] args) {
        printHeading("Flaky HTTP demonstration");

        demonstrateGuaranteedFailure();
        demonstrateLatencyOnly();
        demonstrateUrlFiltering();
        demonstrateAsyncRequests();

        printHeading("Demonstration complete");
    }

    /**
     * Demonstrates a deterministic synthetic failure and empty-body conversion.
     *
     * <p>A failure rate of {@code 1.0} guarantees that the delegate is not
     * invoked. Consequently this scenario succeeds without network access. The
     * configured {@code BodyHandler<String>} converts the synthetic empty body
     * to an empty, non-null string.</p>
     */
    private static void demonstrateGuaranteedFailure() {
        printHeading("1. Guaranteed synthetic failure (no network I/O)");

        FlakyConfig config = FlakyConfig.builder()
                .failureRate(1.0)
                .errorStatus(503)
                .targetUrls(TARGET_PATTERN)
                .build();

        HttpRequest request = get(TARGET_URI);

        try (FlakyHttpClient client =
                     new FlakyHttpClient(HttpClient.newHttpClient(), config)) {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.printf("status=%d, bodyEmpty=%s, contentLength=%s%n",
                    response.statusCode(),
                    response.body().isEmpty(),
                    response.headers().firstValue("content-length").orElse("missing"));
            System.out.println("The delegate was skipped because failureRate is 1.0.");
        } catch (Exception error) {
            printFailure(error);
        }
    }

    /**
     * Demonstrates fixed synchronous latency without injected failures.
     *
     * <p>The request is delayed for at least 250 milliseconds and then delegated
     * because the failure rate is {@code 0.0}. The measured duration also
     * includes DNS, connection establishment, TLS, and remote-server time.</p>
     */
    private static void demonstrateLatencyOnly() {
        printHeading("2. Fixed latency followed by a real request");

        FlakyConfig config = FlakyConfig.builder()
                .failureRate(0.0)
                .latency(LatencyStrategy.fixed(250))
                .targetUrls(TARGET_PATTERN)
                .build();

        try (FlakyHttpClient client =
                     new FlakyHttpClient(HttpClient.newHttpClient(), config)) {
            sendAndPrint(client, get(TARGET_URI), "targeted request");
        }
    }

    /**
     * Demonstrates that URL targeting uses a complete regular-expression match.
     *
     * <p>The configuration would always return {@code 429} for example.com, but
     * the request is sent to iana.org. Because the URI does not match, both
     * failure injection and the 500-millisecond artificial delay are bypassed.</p>
     */
    private static void demonstrateUrlFiltering() {
        printHeading("3. Non-targeted URL bypasses all injection");

        FlakyConfig config = FlakyConfig.builder()
                .failureRate(1.0)
                .latency(LatencyStrategy.fixed(500))
                .errorStatus(429)
                .targetUrls(TARGET_PATTERN)
                .build();

        try (FlakyHttpClient client =
                     new FlakyHttpClient(HttpClient.newHttpClient(), config)) {
            sendAndPrint(client, get(NON_TARGET_URI), "non-targeted request");
            System.out.println("Any observed time is real network time, not injected latency.");
        }
    }

    /**
     * Demonstrates concurrent asynchronous injection with a borrowed scheduler.
     *
     * <p>Five requests are started together. Each independently receives a
     * delay from 100 through 400 milliseconds and has a 40 percent probability
     * of receiving a synthetic {@code 503}. Calls that do not fail are delegated
     * to example.com. Output order reflects completion order rather than request
     * order.</p>
     *
     * <p>Closing the client does not close the explicitly supplied scheduler;
     * this method shuts it down in its own {@code finally} block to demonstrate
     * the ownership contract.</p>
     */
    private static void demonstrateAsyncRequests() {
        printHeading("4. Concurrent async requests with jitter");

        FlakyConfig config = FlakyConfig.builder()
                .failureRate(0.40)
                .latency(LatencyStrategy.random(100, 400))
                .errorStatus(503)
                .targetUrls(TARGET_PATTERN)
                .build();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        try (FlakyHttpClient client =
                     new FlakyHttpClient(HttpClient.newHttpClient(), config, scheduler)) {
            List<CompletableFuture<Void>> observations = new ArrayList<>();

            for (int requestNumber = 1; requestNumber <= 5; requestNumber++) {
                final int number = requestNumber;
                final long startedAt = System.nanoTime();

                CompletableFuture<Void> observation = client
                        .sendAsync(get(TARGET_URI), HttpResponse.BodyHandlers.ofString())
                        .handle((response, error) -> {
                            long elapsedMillis = elapsedMillis(startedAt);
                            if (error != null) {
                                System.out.printf("async #%d: exception=%s, elapsed=%dms%n",
                                        number, rootCause(error), elapsedMillis);
                            } else {
                                System.out.printf("async #%d: status=%d, bodyLength=%d, elapsed=%dms%n",
                                        number, response.statusCode(), response.body().length(), elapsedMillis);
                            }
                            return null;
                        });
                observations.add(observation);
            }

            CompletableFuture.allOf(observations.toArray(new CompletableFuture<?>[0])).join();
        } finally {
            scheduler.shutdown();
            System.out.println("Caller-owned scheduler shut down by the caller.");
        }
    }

    /**
     * Sends one request synchronously and prints its status and elapsed time.
     * Network and interruption failures are converted to instructional output.
     *
     * @param client client used to send the request
     * @param request request to send
     * @param label human-readable label included in output
     */
    private static void sendAndPrint(FlakyHttpClient client, HttpRequest request, String label) {
        long startedAt = System.nanoTime();
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.printf("%s: status=%d, elapsed=%dms%n",
                    label, response.statusCode(), elapsedMillis(startedAt));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            printFailure(error);
        } catch (Exception error) {
            printFailure(error);
        }
    }

    /**
     * Creates a GET request with a finite timeout for the real network portion.
     * Artificial latency occurs before delegation and is not included in this
     * {@link HttpRequest} timeout.
     *
     * @param uri destination URI
     * @return a new immutable GET request
     */
    private static HttpRequest get(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
    }

    /**
     * Converts a {@link System#nanoTime()} start value into elapsed milliseconds.
     *
     * @param startedAt value previously returned by {@code System.nanoTime()}
     * @return elapsed monotonic time in milliseconds
     */
    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    /**
     * Walks through completion wrappers to find the most useful error for output.
     *
     * @param error exception reported by an asynchronous stage
     * @return deepest available cause rendered as text
     */
    private static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.toString();
    }

    /**
     * Prints an exception without terminating the demonstration.
     *
     * @param error failure to report
     */
    private static void printFailure(Throwable error) {
        System.out.println("request failed: " + rootCause(error));
    }

    /**
     * Prints a visually distinct scenario heading.
     *
     * @param title heading text
     */
    private static void printHeading(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }
}
