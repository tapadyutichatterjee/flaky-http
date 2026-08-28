package com.tapadyuti.flakyhttp;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Configuration for the {@link FlakyHttpClient}.
 * This class is immutable and should be created using the {@link Builder}.
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
     * Returns the probability of failure (0.0 to 1.0).
     *
     * @return the failure rate.
     */
    public double getFailureRate() {
        return failureRate;
    }

    /**
     * Returns the strategy used to calculate artificial latency.
     *
     * @return the latency strategy, or null if no latency is configured.
     */
    public LatencyStrategy getLatencyStrategy() {
        return latencyStrategy;
    }

    /**
     * Returns the HTTP status code to be returned during a failure.
     *
     * @return the error status code.
     */
    public int getErrorStatus() {
        return errorStatus;
    }

    /**
     * Returns the compiled regex pattern for targeting specific URLs.
     *
     * @return the target URL pattern, or null if no specific target is configured.
     */
    public Pattern getTargetUrlPattern() {
        return targetUrlPattern;
    }

    /**
     * Creates a new builder for {@link FlakyConfig}.
     *
     * @return a new Builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent Builder for {@link FlakyConfig}.
     */
    public static class Builder {
        private double failureRate = 0.0;
        private LatencyStrategy latencyStrategy = null;
        private int errorStatus = 500;
        private Pattern targetUrlPattern = null;

        /**
         * Sets the failure rate.
         *
         * @param rate A value between 0.0 and 1.0.
         * @return the builder instance.
         * @throws IllegalArgumentException if rate is not between 0.0 and 1.0.
         */
        public Builder failureRate(double rate) {
            if (rate < 0.0 || rate > 1.0) {
                throw new IllegalArgumentException("Failure rate must be between 0.0 and 1.0");
            }
            this.failureRate = rate;
            return this;
        }

        /**
         * Sets the latency strategy.
         *
         * @param strategy The {@link LatencyStrategy} to use.
         * @return the builder instance.
         */
        public Builder latency(LatencyStrategy strategy) {
            this.latencyStrategy = strategy;
            return this;
        }

        /**
         * Sets the HTTP status code to return on failure.
         *
         * @param statusCode The HTTP status code (e.g., 500, 429).
         * @return the builder instance.
         */
        public Builder errorStatus(int statusCode) {
            this.errorStatus = statusCode;
            return this;
        }

        /**
         * Sets the regex pattern to target specific URLs for chaos injection.
         *
         * @param regex The regular expression to match against request URLs.
         * @return the builder instance.
         * @throws IllegalArgumentException if regex is null.
         */
        public Builder targetUrls(String regex) {
            if (regex == null) {
                throw new IllegalArgumentException("Target URL regex cannot be null");
            }
            this.targetUrlPattern = Pattern.compile(regex);
            return this;
        }

        /**
         * Builds the {@link FlakyConfig} instance.
         *
         * @return a fully configured FlakyConfig object.
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
