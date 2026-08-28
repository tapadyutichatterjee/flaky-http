package com.tapadyuti.flakyhttp;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Strategy for calculating the artificial latency to be injected into HTTP requests.
 */
public interface LatencyStrategy {
    /**
     * Calculates the current delay in milliseconds.
     *
     * @return the delay in milliseconds.
     */
    long getDelayMillis();

    /**
     * Creates a fixed latency strategy.
     *
     * @param delayMillis The fixed delay in milliseconds.
     * @return A {@link FixedLatencyStrategy} instance.
     */
    static LatencyStrategy fixed(long delayMillis) {
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must not be negative");
        }
        return new FixedLatencyStrategy(delayMillis);
    }

    /**
     * Creates a random latency strategy (jitter) between a minimum and maximum range.
     *
     * @param minMillis The minimum delay in milliseconds.
     * @param maxMillis The maximum delay in milliseconds.
     * @return A {@link RandomLatencyStrategy} instance.
     * @throws IllegalArgumentException if minMillis > maxMillis.
     */
    static LatencyStrategy random(long minMillis, long maxMillis) {
        if (minMillis < 0 || maxMillis < 0 || minMillis > maxMillis) {
            throw new IllegalArgumentException("latency bounds must be non-negative and minMillis must not exceed maxMillis");
        }
        return new RandomLatencyStrategy(minMillis, maxMillis);
    }

    class FixedLatencyStrategy implements LatencyStrategy {
        private final long delayMillis;

        FixedLatencyStrategy(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public long getDelayMillis() {
            return delayMillis;
        }

        @Override public boolean equals(Object other) {
            return other instanceof FixedLatencyStrategy
                    && delayMillis == ((FixedLatencyStrategy) other).delayMillis;
        }
        @Override public int hashCode() { return Long.hashCode(delayMillis); }
        @Override public String toString() { return "fixed(" + delayMillis + "ms)"; }
    }

    class RandomLatencyStrategy implements LatencyStrategy {
        private final long minMillis;
        private final long maxMillis;

        RandomLatencyStrategy(long minMillis, long maxMillis) {
            this.minMillis = minMillis;
            this.maxMillis = maxMillis;
        }

        @Override
        public long getDelayMillis() {
            if (minMillis == maxMillis) {
                return minMillis;
            }
            if (maxMillis == Long.MAX_VALUE) {
                long candidate;
                do {
                    candidate = ThreadLocalRandom.current().nextLong() >>> 1;
                } while (candidate < minMillis);
                return candidate;
            }
            return ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof RandomLatencyStrategy)) return false;
            RandomLatencyStrategy that = (RandomLatencyStrategy) other;
            return minMillis == that.minMillis && maxMillis == that.maxMillis;
        }
        @Override public int hashCode() { return 31 * Long.hashCode(minMillis) + Long.hashCode(maxMillis); }
        @Override public String toString() { return "random(" + minMillis + "ms," + maxMillis + "ms)"; }
    }
}
