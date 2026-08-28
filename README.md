# Flaky HTTP (Java 11)

[![Build](https://github.com/tapadyutichatterjee/flaky-http/actions/workflows/ci.yml/badge.svg?branch=master&event=push)](https://github.com/tapadyutichatterjee/flaky-http/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.tapadyuti/flaky-http?label=Maven%20Central)](https://central.sonatype.com/artifact/com.tapadyuti/flaky-http)
[![Javadocs](https://javadoc.io/badge2/com.tapadyuti/flaky-http/javadoc.svg)](https://javadoc.io/doc/com.tapadyuti/flaky-http)
[![Java 11](https://img.shields.io/badge/Java-11-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/11/)
[![License](https://img.shields.io/github/license/tapadyutichatterjee/flaky-http)](https://github.com/tapadyutichatterjee/flaky-http/blob/master/LICENSE)
[![Publish](https://github.com/tapadyutichatterjee/flaky-http/actions/workflows/publish.yml/badge.svg)](https://github.com/tapadyutichatterjee/flaky-http/actions/workflows/publish.yml)

Flaky HTTP is a small Java library for deliberately making HTTP calls less reliable. It wraps Java's standard `java.net.http.HttpClient` and injects latency or synthetic HTTP error responses before selected requests reach the network.

Use it to exercise retries, timeouts, circuit breakers, fallbacks, metrics, and other resilience behavior under controlled failure conditions.

> Flaky HTTP is a testing tool. Do not enable deliberate failure injection in production unless you have intentionally designed and safeguarded a chaos-engineering experiment.

## Features

- Fixed or randomized artificial latency.
- A configurable probability of failure from `0.0` to `1.0`.
- Synthetic client or server error statuses from `400` to `599`.
- Full-URI targeting with regular expressions.
- Synchronous and asynchronous request APIs.
- Cancellation propagation for delayed asynchronous calls.
- Correct empty-body conversion through the caller's `BodyHandler`.
- No runtime dependencies beyond Java 11.

## Where it is useful

Flaky HTTP is most useful when your application already talks to another service through Java's `HttpClient` and you want to verify how the surrounding application behaves when that dependency becomes slow or returns errors.

| Use case | Example configuration | What you can verify |
| --- | --- | --- |
| Integration testing | Target the test environment's API and inject intermittent `503` responses | The full application retries, recovers, or presents the expected error |
| Retry-policy testing | Use `failureRate(1.0)` or a high failure rate | Attempt limits, backoff timing, retryable status handling, and eventual failure |
| Timeout testing | Add latency longer than an application-level deadline or future timeout | Timeout propagation, cleanup, cancellation, and user-facing behavior |
| Circuit-breaker testing | Return repeated `500` or `503` responses | Open, half-open, and recovery transitions around an HTTP dependency |
| Rate-limit handling | Return `429` responses | Backpressure, retry suppression, metrics, and fallback behavior |
| Fallback and cache testing | Fail calls to one targeted endpoint | Cached or degraded responses are selected correctly |
| Observability testing | Mix random latency with intermittent errors | Logs, traces, alerts, dashboards, and service-level indicators reflect failures |
| Bulkhead testing | Combine async latency with concurrent requests | Slow dependencies do not exhaust unrelated application resources |
| Local development and demos | Inject predictable failures without changing a real service | Error screens and resilience features can be demonstrated repeatedly |
| Regression testing | Use deterministic `0.0` or `1.0` failure rates | Previously fixed failure-handling behavior remains covered by automated tests |

### Integration-test example

The following test verifies that an application-level fallback is used when its upstream orders API returns a synthetic `503`. `OrderResult` and `orderService` represent application code in this example.

```java
@Test
void fallsBackWhenOrdersApiIsUnavailable() throws Exception {
    FlakyConfig chaos = FlakyConfig.builder()
            .failureRate(1.0)
            .errorStatus(503)
            .targetUrls("http://localhost:8080/orders/.*")
            .build();

    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8080/orders/42"))
            .GET()
            .build();

    try (FlakyHttpClient client =
                 new FlakyHttpClient(HttpClient.newHttpClient(), chaos)) {
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        OrderResult result = orderService.handle(response);

        assertEquals(503, response.statusCode());
        assertTrue(result.isFallback());
    }
}
```

Because the failure rate is `1.0`, this test is deterministic and the request never reaches the local server. Set the rate to `0.0` when the same test should exercise the real integration endpoint with latency only.

### What it does not simulate

Flaky HTTP operates immediately above `HttpClient`; it is not a proxy or a faulty server. Synthetic HTTP responses are appropriate for application-level resilience tests, but they do not reproduce every network failure. Use a proxy, containerized fault injector, or network emulator when you specifically need to test DNS failures, connection refusal, TLS negotiation errors, connection resets, truncated payloads, bandwidth limits, or malformed wire-level HTTP.

Artificial latency occurs before delegation, so an `HttpRequest` timeout enforced by the wrapped client does not include that delay. Use an application-level deadline or timeout around the complete `send`/`sendAsync` operation when testing end-to-end timeout behavior.

## Installation

After version `1.0.0` is available from Maven Central, add:

```xml
<dependency>
    <groupId>com.tapadyuti</groupId>
    <artifactId>flaky-http</artifactId>
    <version>1.0.0</version>
</dependency>
```

For Gradle:

```gradle
testImplementation("com.tapadyuti:flaky-http:1.0.0")
```

Until the first Central release is published, clone the repository and install it locally with `mvn install`.

## Quick start

```java
import com.tapadyuti.flakyhttp.FlakyConfig;
import com.tapadyuti.flakyhttp.FlakyHttpClient;
import com.tapadyuti.flakyhttp.LatencyStrategy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

FlakyConfig config = FlakyConfig.builder()
        .failureRate(0.30)                         // 30% synthetic failures
        .latency(LatencyStrategy.random(100, 500)) // 100–500 ms on every targeted call
        .errorStatus(503)                          // Service Unavailable
        .targetUrls("https://api\\.example\\.com/.*")
        .build();

HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.example.com/orders/42"))
        .GET()
        .build();

try (FlakyHttpClient client = new FlakyHttpClient(HttpClient.newHttpClient(), config)) {
    HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());

    System.out.println(response.statusCode());
}
```

For each targeted call, latency is applied first. The client then makes an independent failure decision. On failure, it returns a synthetic response without making a network request; otherwise, it delegates to the wrapped `HttpClient`.

## Common scenarios

### Always fail to test retry behavior

```java
FlakyConfig config = FlakyConfig.builder()
        .failureRate(1.0)
        .errorStatus(429)
        .build();
```

With no URL pattern configured, every request is targeted. A failure rate of `1.0` guarantees a synthetic response, which makes this configuration deterministic and useful in tests.

### Add latency without failures

```java
FlakyConfig config = FlakyConfig.builder()
        .failureRate(0.0)
        .latency(LatencyStrategy.fixed(250))
        .build();
```

This delays each targeted call by 250 milliseconds and then sends the real request.

### Add jitter

```java
FlakyConfig config = FlakyConfig.builder()
        .latency(LatencyStrategy.random(50, 300))
        .build();
```

Both endpoints are inclusive, so each call receives a delay from 50 through 300 milliseconds.

### Target one host or path

`targetUrls` is a Java regular expression matched against the complete value of `request.uri().toString()`. Use `.*` explicitly when you want a partial match.

```java
// Every HTTPS request to api.example.com
.targetUrls("https://api\\.example\\.com/.*")

// Only the /payments path on that host, with or without a query string
.targetUrls("https://api\\.example\\.com/payments(?:\\?.*)?")

// Any URI containing /experimental/
.targetUrls(".*/experimental/.*")
```

Requests that do not match bypass all latency and failure injection.

### Send asynchronously

```java
try (FlakyHttpClient client = new FlakyHttpClient(HttpClient.newHttpClient(), config)) {
    client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response ->
                    System.out.printf("status=%d body=%s%n",
                            response.statusCode(), response.body()))
            .join();
}
```

Artificial async latency uses a scheduler rather than blocking the calling thread. Cancelling the returned `CompletableFuture` cancels a pending delay and attempts to cancel a delegate request that has already started.

### Supply a shared scheduler

```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

try {
    try (FlakyHttpClient client =
                 new FlakyHttpClient(HttpClient.newHttpClient(), config, scheduler)) {
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();
    }
} finally {
    scheduler.shutdown();
}
```

The two-argument constructor creates an internal daemon scheduler, which is shut down by `close()` or `shutdown()`. A scheduler passed to the three-argument constructor remains owned by the caller and is never shut down by the client.

## Configuration reference

| Setting | Default | Accepted values | Effect |
| --- | --- | --- | --- |
| `failureRate(rate)` | `0.0` | Finite number from `0.0` to `1.0` | Probability that a targeted request receives a synthetic error |
| `latency(strategy)` | None | Non-null `LatencyStrategy` | Delay applied to every targeted request before failure selection |
| `errorStatus(code)` | `500` | `400`–`599` | Status returned for a synthetic failure |
| `targetUrls(regex)` | All URLs | Valid, non-null Java regex | Limits injection to complete URI matches |

You can also provide a custom latency strategy:

```java
LatencyStrategy latency = () -> System.currentTimeMillis() % 200;
```

Strategies may be called concurrently and should therefore be thread-safe and return non-negative delays.

## Synthetic response semantics

A synthetic failure has:

- The configured error status.
- An empty body converted by the supplied `BodyHandler`—for example, `""` for `ofString()` and an empty array for `ofByteArray()`.
- A `Content-Length: 0` header.
- The original request and URI.
- The wrapped client's preferred HTTP version.
- No SSL session or previous response.

The body handler still participates in constructing the response, but no remote headers or payload exist because no network call occurs.

## Important design notes

- Flaky HTTP uses composition; `FlakyHttpClient` is not a subclass of `HttpClient`. Call sites must use the wrapper type directly or place it behind an application-owned abstraction.
- Random failure selection uses `ThreadLocalRandom`. Guaranteed rates (`0.0` and `1.0`) are best for deterministic unit tests.
- Latency and failure configuration is immutable after construction.
- A synchronous thread interrupted during artificial latency receives `InterruptedException`, and the real request is not sent.
- Calling `sendAsync` after the internally owned scheduler has been shut down may throw `RejectedExecutionException` for a targeted request.

## Building and testing

Requirements:

- JDK 11 or newer.
- Maven 3.6 or newer.

Run the complete verification suite:

```shell
mvn clean verify
```

Run the demonstration program from an IDE, or compile the project and execute `com.tapadyuti.flakyhttp.FlakyHttpDemo`. The demo makes real requests to public websites, so its results depend on network access.

### Release build without publishing

Generate the binary, sources, and Javadoc JARs without uploading them:

```shell
mvn -Prelease -Dgpg.skip=true verify
```

The artifacts are written to `target/`:

```text
flaky-http-1.0.0.jar
flaky-http-1.0.0-sources.jar
flaky-http-1.0.0-javadoc.jar
```

## Contributing

Issues, bug reports, documentation improvements, and pull requests are welcome.

1. Fork the repository and create a focused branch.
2. Add or update tests for behavior changes.
3. Run `mvn clean verify` locally.
4. Open a pull request explaining the motivation, observable behavior, and relevant tradeoffs.

Please keep changes compatible with Java 11, avoid new runtime dependencies unless they provide clear value, and keep failure behavior explicit and testable. For security-sensitive reports, avoid opening a public issue until a private reporting channel is published.

## Release process

Publishing is configured for the [Sonatype Central Publisher Portal](https://central.sonatype.com/).

Maintainers normally publish through the `Publish to Maven Central` GitHub Actions workflow:

1. Verify that the version in `pom.xml` is the intended release version.
2. Run `mvn clean verify` locally.
3. Create a GitHub Release with a tag such as `v1.0.0`, or manually run the publishing workflow with an explicit version.
4. GitHub Actions builds, tests, generates sources and Javadocs, signs every artifact, uploads the bundle, and waits for Central publication.

The equivalent local deployment command is:

```shell
mvn -Prelease deploy
```

Local deployment requires Central credentials in Maven `settings.xml`, an available GPG private key, and its passphrase. Do not place credentials or private keys in the repository.

Published Maven Central versions are immutable and must never be reused. Fixes require a new version.

## License

Flaky HTTP is available under the [Apache License 2.0](LICENSE).
