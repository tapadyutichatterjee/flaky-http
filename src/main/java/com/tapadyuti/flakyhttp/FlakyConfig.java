package com.tapadyuti.flakyhttp;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable configuration that controls how a {@link FlakyHttpClient} injects
 * latency and synthetic HTTP failures.
 *
 * <p>Instances are created with {@link #builder()}. The default configuration
 * targets every request, adds no latency, never injects a failure, and uses
 * status {@code 500} if failure injection is later enabled.</p>
 *
 * <p>The class is immutable and safe to share between threads. A configured
 * {@link LatencyStrategy}, however, is application-supplied behavior and must
 * itself be thread-safe when the client is used concurrently.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * FlakyConfig config = FlakyConfig.builder()
 *         .failureRate(0.25)
 *         .latency(LatencyStrategy.random(50, 200))
 *         .errorStatus(503)
 *         .targetUrls("https://api\\.example\\.com/.*")
 *         .build();
 * }</pre>
 *
 * @see FlakyHttpClient
 * @see LatencyStrategy
 */
public final class FlakyConfig {
    private final double failureRate;
    private final LatencyStrategy latencyStrategy;
    private final int errorStatus;
    private final Pattern targetUrlPattern;

    private FlakyConfig(Builder builder) {
        this.failureRate = builder.failureRate;
        this.latencyStrategy = builder.latencyStrategy;
        this.errorStatus = builder.errorStatus;
        this.targetUrlPattern = builder.targetUrlPattern;
    }

    /**
     * Returns the probability that a targeted request receives a synthetic
     * response instead of being delegated to the underlying HTTP client.
     * A value of {@code 0.0} disables failures; {@code 1.0} guarantees them.
     *
     * @return the failure probability in the inclusive range {@code [0.0, 1.0]}
     */
    public double getFailureRate() {
        return failureRate;
    }

    /**
     * Returns the strategy used to calculate artificial latency for each
     * targeted request. Latency is applied before the failure decision, so
     * both synthetic failures and delegated requests are delayed.
     *
     * @return the latency strategy, or {@code null} when latency is disabled
     */
    public LatencyStrategy getLatencyStrategy() {
        return latencyStrategy;
    }

    /**
     * Returns the HTTP error status used for synthetic responses.
     *
     * @return an HTTP status in the range {@code 400} through {@code 599}
     */
    public int getErrorStatus() {
        return errorStatus;
    }

    /**
     * Returns the compiled pattern used to select requests for injection.
     * The pattern is evaluated with {@link java.util.regex.Matcher#matches()},
     * against the complete value of {@link java.net.http.HttpRequest#uri()}
     * converted to a string.
     *
     * @return the target URL pattern, or {@code null} when every URL is targeted
     */
    public Pattern getTargetUrlPattern() {
        return targetUrlPattern;
    }

    /**
     * Creates a builder initialized with the documented defaults.
     *
     * @return a new, independent builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link FlakyConfig}.
     *
     * <p>A builder is mutable and not thread-safe. The object returned by
     * {@link #build()} is immutable; later changes to the builder do not alter
     * previously built configurations.</p>
     */
    public static class Builder {
        private double failureRate = 0.0;
        private LatencyStrategy latencyStrategy = null;
        private int errorStatus = 500;
        private Pattern targetUrlPattern = null;

        /**
         * Creates a builder with no failures, no latency, status {@code 500},
         * and no URL restriction.
         */
        public Builder() {
        }

        /**
         * Sets the independent probability of failure for each targeted call.
         *
         * @param rate a finite value in the inclusive range {@code [0.0, 1.0]}
         * @return this builder
         * @throws IllegalArgumentException if {@code rate} is non-finite or
         *                                  outside the accepted range
         */
        public Builder failureRate(double rate) {
            if (!Double.isFinite(rate) || rate < 0.0 || rate > 1.0) {
                throw new IllegalArgumentException("Failure rate must be between 0.0 and 1.0");
            }
            this.failureRate = rate;
            return this;
        }

        /**
         * Sets the strategy evaluated once for each targeted request.
         *
         * @param strategy a non-null, thread-safe latency strategy
         * @return this builder
         * @throws NullPointerException if {@code strategy} is {@code null}
         */
        public Builder latency(LatencyStrategy strategy) {
            this.latencyStrategy = Objects.requireNonNull(strategy, "strategy");
            return this;
        }

        /**
         * Sets the status code returned by a synthetic failure response.
         *
         * @param statusCode an HTTP client or server error from {@code 400} to
         *                   {@code 599}, inclusive
         * @return this builder
         * @throws IllegalArgumentException if the status is outside
         *                                  {@code 400} through {@code 599}
         */
        public Builder errorStatus(int statusCode) {
            if (statusCode < 400 || statusCode > 599) {
                throw new IllegalArgumentException("Error status must be between 400 and 599");
            }
            this.errorStatus = statusCode;
            return this;
        }

        /**
         * Sets the regular expression used to target requests for injection.
         * The expression must match the complete URI string. For example,
         * {@code https://api\.example\.com/.*} targets every path on that host.
         *
         * @param regex a non-null Java regular expression
         * @return this builder
         * @throws IllegalArgumentException if {@code regex} is {@code null}
         * @throws java.util.regex.PatternSyntaxException if the expression is invalid
         */
        public Builder targetUrls(String regex) {
            if (regex == null) {
                throw new IllegalArgumentException("Target URL regex cannot be null");
            }
            this.targetUrlPattern = Pattern.compile(regex);
            return this;
        }

        /**
         * Creates an immutable snapshot of the current builder state.
         *
         * @return a new configuration
         */
        public FlakyConfig build() {
            return new FlakyConfig(this);
        }
    }

    @Override
    public String toString() {
        return "FlakyConfig{" +
                "failureRate=" + failureRate +
                ", latencyStrategy=" + latencyStrategy +
                ", errorStatus=" + errorStatus +
                ", targetUrlPattern=" + targetUrlPattern +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FlakyConfig that = (FlakyConfig) o;
        return Double.compare(that.failureRate, failureRate) == 0 &&
                errorStatus == that.errorStatus &&
                Objects.equals(latencyStrategy, that.latencyStrategy) &&
                Objects.equals(targetUrlPattern, that.targetUrlPattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(failureRate, latencyStrategy, errorStatus, targetUrlPattern);
    }
}
