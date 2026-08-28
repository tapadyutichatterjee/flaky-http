# Flaky HTTP

Flaky HTTP wraps Java 11's `HttpClient` to inject latency and synthetic HTTP errors into selected requests. It is designed for testing retries, timeouts, circuit breakers, and other resilience behavior.

## Usage

```java
FlakyConfig config = FlakyConfig.builder()
        .failureRate(0.30)
        .latency(LatencyStrategy.random(100, 500))
        .errorStatus(503)
        .targetUrls("https://api\\.example\\.com/.*")
        .build();

try (FlakyHttpClient client = new FlakyHttpClient(HttpClient.newHttpClient(), config)) {
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
}
```

`failureRate` ranges from `0.0` to `1.0`. Error statuses must be in the `400`–`599` range, latency must be non-negative, and the URL expression is matched against the complete URI. If no URL expression is configured, every request is eligible for injection.

Synthetic errors contain an empty body produced through the supplied `BodyHandler`, an empty-body `Content-Length` header, and the delegate client's preferred HTTP version. The real network request is not made.

Both `send` and `sendAsync` are supported. Cancelling a delayed asynchronous call prevents delegation and attempts to cancel a delegate call that has already started. An interrupted synchronous delay throws `InterruptedException` without sending the request.

## Scheduler lifecycle

The two-argument constructor creates an internal daemon scheduler. Close the client, or call `shutdown()`, when it is no longer needed.

The three-argument constructor accepts a caller-owned `ScheduledExecutorService`. Closing the client does not shut down a caller-owned scheduler.

## Build

```shell
mvn clean verify
```

The library targets Java 11 and has no runtime dependencies.

## Release

GitHub releases publish through the Central Publisher Portal. The repository must have a verified `com.tapadyuti` namespace and the `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`, `GPG_PRIVATE_KEY`, and `GPG_PASSPHRASE` secrets configured. A release tag such as `v1.0.0` becomes Maven version `1.0.0`; manual runs require an explicit version.

## License

Apache License 2.0. See [LICENSE](LICENSE).
