package com.tapadyuti.flakyhttp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Demonstration of how to use the FlakyHttp library as a drop-in replacement
 * for the standard {@link HttpClient}.
 */
public class FlakyHttpDemo {
    public static void main(String[] args) {
        System.out.println("=== Starting FlakyHttp Demo ===\n");

        // 1. Configure the chaos parameters
        FlakyConfig config = FlakyConfig.builder()
                .failureRate(0.3) // 30% chance of failure
                .latency(LatencyStrategy.random(100, 500)) // 100ms to 500ms jitter
                .errorStatus(503) // Service Unavailable
                .targetUrls(".*google\\.com.*") // Only affect requests to google.com
                .build();

        // 2. Create the real HttpClient
        HttpClient realClient = HttpClient.newHttpClient();

        // 3. Wrap it with FlakyHttpClient
        FlakyHttpClient flakyClient = new FlakyHttpClient(realClient, config);

        // 4. Create a request to a targeted URL
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://www.google.com"))
                .GET()
                .build();

        System.out.println("Sending 5 requests to a targeted URL (google.com)...");
        for (int i = 1; i <= 5; i++) {
            try {
                long start = System.currentTimeMillis();
                HttpResponse<String> response = flakyClient.send(request, HttpResponse.BodyHandlers.ofString());
                long end = System.currentTimeMillis();

                System.out.printf("Request #%d: Status [%d] | Time [%dms]%n", 
                                  i, response.statusCode(), (end - start));
            } catch (Exception e) {
                System.err.println("Request #" + i + " failed: " + e.getMessage());
            }
        }

        // 5. Create a request to a non-targeted URL (should be fast and stable)
        HttpRequest stableRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://example.com"))
                .GET()
                .build();

        System.out.println("\nSending 5 requests to a non-targeted URL (example.com)...");
        for (int i = 1; i <= 5; i++) {
            try {
                long start = System.currentTimeMillis();
                HttpResponse<String> response = flakyClient.send(stableRequest, HttpResponse.BodyHandlers.ofString());
                long end = System.currentTimeMillis();

                System.out.printf("Request #%d: Status [%d] | Time [%dms]%n", 
                                  i, response.statusCode(), (end - start));
            } catch (Exception e) {
                System.err.println("Request #" + i + " failed: " + e.getMessage());
            }
        }

        // 6. Demonstrate Async execution
        System.out.println("\nSending an async request...");
        CompletableFuture<HttpResponse<String>> asyncFuture = flakyClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        asyncFuture.thenAccept(res -> System.out.println("Async Response received: Status " + res.statusCode()))
                   .join();

        flakyClient.shutdown();
        System.out.println("\n=== Demo Completed ===");
    }
}
