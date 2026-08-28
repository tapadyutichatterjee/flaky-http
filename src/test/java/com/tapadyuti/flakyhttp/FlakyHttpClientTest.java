package com.tapadyuti.flakyhttp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlakyHttpClientTest {

    @Mock
    private HttpClient mockDelegate;

    @Mock
    private HttpResponse<String> mockResponse;

    private HttpRequest request;

    @BeforeEach
    void setUp() {
        request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.example.com/test"))
                .GET()
                .build();
    }

    @Test
    void testLatencyInjection() throws IOException, InterruptedException {
        long delay = 200L;
        FlakyConfig config = FlakyConfig.builder()
                .latency(LatencyStrategy.fixed(delay))
                .failureRate(0.0) // No failures, just latency
                .build();

        FlakyHttpClient flakyClient = new FlakyHttpClient(mockDelegate, config);
        
        when(mockDelegate.send(any(), any())).thenReturn(mockResponse);

        Instant start = Instant.now();
        flakyClient.send(request, HttpResponse.BodyHandlers.ofString());
        Instant end = Instant.now();

        long actualDuration = Duration.between(start, end).toMillis();
        assertTrue(actualDuration >= delay, "Expected latency of at least " + delay + "ms, but got " + actualDuration + "ms");
    }

    @Test
    void testFailureRateTriggersMockResponse() throws IOException, InterruptedException {
        FlakyConfig config = FlakyConfig.builder()
                .failureRate(1.0) // 100% failure
                .errorStatus(429)
                .build();

        FlakyHttpClient flakyClient = new FlakyHttpClient(mockDelegate, config);

        HttpResponse<String> response = flakyClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(429, response.statusCode());
        verifyNoInteractions(mockDelegate);
    }

    @Test
    void testZeroFailureRateDelegatesToRealClient() throws IOException, InterruptedException {
        FlakyConfig config = FlakyConfig.builder()
                .failureRate(0.0) // 0% failure
                .build();

        FlakyHttpClient flakyClient = new FlakyHttpClient(mockDelegate, config);
        
        when(mockDelegate.send(any(), any())).thenReturn(mockResponse);

        HttpResponse<String> response = flakyClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(mockResponse, response);
        verify(mockDelegate, times(1)).send(eq(request), any());
    }

    @Test
    void testTargetUrlFiltering() throws IOException, InterruptedException {
        // Config only targets "google.com"
        FlakyConfig config = FlakyConfig.builder()
                .failureRate(1.0)
                .targetUrls(".*google\\.com.*")
                .build();

        FlakyHttpClient flakyClient = new FlakyHttpClient(mockDelegate, config);

        // Request to example.com (non-target)
        HttpRequest stableRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://example.com"))
                .GET()
                .build();

        when(mockDelegate.send(any(), any())).thenReturn(mockResponse);

        HttpResponse<String> response = flakyClient.send(stableRequest, HttpResponse.BodyHandlers.ofString());

        // Should NOT have failed because URL doesn't match
        assertEquals(mockResponse, response);
        verify(mockDelegate, times(1)).send(eq(stableRequest), any());
    }
}
