/**
 * Provides an HTTP-client wrapper for application-level resilience and chaos
 * testing.
 *
 * <p>The package is centered on three types:</p>
 * <ul>
 *   <li>{@link com.tapadyuti.flakyhttp.FlakyHttpClient} executes synchronous or
 *       asynchronous requests while injecting configured behavior;</li>
 *   <li>{@link com.tapadyuti.flakyhttp.FlakyConfig} defines failure probability,
 *       latency, error status, and URL selection;</li>
 *   <li>{@link com.tapadyuti.flakyhttp.LatencyStrategy} supplies fixed, random,
 *       or application-defined delays.</li>
 * </ul>
 *
 * <p>Failures produced by this package are synthetic HTTP responses. They are
 * suitable for testing retries, circuit breakers, fallbacks, rate-limit
 * handling, and application-level deadlines. They do not emulate transport
 * faults such as DNS errors, TLS failures, connection resets, packet loss, or
 * malformed wire-level responses.</p>
 *
 * <h2>Basic usage</h2>
 * <pre>{@code
 * FlakyConfig config = FlakyConfig.builder()
 *         .failureRate(0.2)
 *         .latency(LatencyStrategy.random(100, 300))
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
 * @since 1.0.0
 */
package com.tapadyuti.flakyhttp;
