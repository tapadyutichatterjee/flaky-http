package com.tapadyuti.flakyhttp;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Supplies an artificial delay for a request targeted by {@link FlakyConfig}.
 *
 * <p>The client evaluates the strategy once per targeted request. Synchronous
 * calls block for the returned duration; asynchronous calls schedule their next
 * step after that duration. The delay is applied before the failure decision,
 * and therefore affects both synthetic and delegated responses.</p>
 *
 * <p>Implementations used by a shared {@link FlakyHttpClient} must be thread-safe
 * and must return a non-negative number of milliseconds. The built-in
 * {@link #fixed(long)} and {@link #random(long, long)} strategies satisfy these
 * requirements.</p>
 *
 * <p>This is a functional interface, so custom behavior can be supplied with a
 * lambda:</p>
 * <pre>{@code
 * LatencyStrategy increasingDelay = () -> 100L;
 * }</pre>
 *
 * @see FlakyConfig.Builder#latency(LatencyStrategy)
 */
@FunctionalInterface
public interface LatencyStrategy {
    /**
     * Calculates the delay for the current targeted request.
     *
     * @return a non-negative delay in milliseconds
     */
    long getDelayMillis();

    /**
     * Creates a strategy that returns the same delay for every request.
     *
     * @param delayMillis the non-negative fixed delay in milliseconds
     * @return an immutable, thread-safe fixed strategy
     * @throws IllegalArgumentException if {@code delayMillis} is negative
     */
    static LatencyStrategy fixed(long delayMillis) {
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must not be negative");
        }
        return new FixedLatencyStrategy(delayMillis);
    }

    /**
     * Creates a strategy that independently selects a uniformly distributed
     * delay from an inclusive range for each invocation.
     *
     * @param minMillis the inclusive, non-negative lower bound in milliseconds
     * @param maxMillis the inclusive, non-negative upper bound in milliseconds
     * @return an immutable, thread-safe random strategy
     * @throws IllegalArgumentException if either bound is negative or
     *                                  {@code minMillis > maxMillis}
     */
    static LatencyStrategy random(long minMillis, long maxMillis) {
        if (minMillis < 0 || maxMillis < 0 || minMillis > maxMillis) {
            throw new IllegalArgumentException("latency bounds must be non-negative and minMillis must not exceed maxMillis");
        }
        return new RandomLatencyStrategy(minMillis, maxMillis);
    }

    /**
     * Immutable strategy returned by {@link LatencyStrategy#fixed(long)}.
     * Instances compare by their configured delay and are safe to share across
     * threads.
     */
    final class FixedLatencyStrategy implements LatencyStrategy {
        private final long delayMillis;

        FixedLatencyStrategy(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        /**
         * Returns the configured fixed delay.
         *
         * @return the fixed delay in milliseconds
         */
        @Override
        public long getDelayMillis() {
            return delayMillis;
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object other) {
            return other instanceof FixedLatencyStrategy
                    && delayMillis == ((FixedLatencyStrategy) other).delayMillis;
        }
        /** {@inheritDoc} */
        @Override public int hashCode() { return Long.hashCode(delayMillis); }
        /**
         * Returns a concise description such as {@code fixed(100ms)}.
         *
         * @return the strategy description
         */
        @Override public String toString() { return "fixed(" + delayMillis + "ms)"; }
    }

    /**
     * Immutable jitter strategy returned by
     * {@link LatencyStrategy#random(long, long)}. Instances compare by their
     * inclusive bounds and are safe to share across threads.
     */
    final class RandomLatencyStrategy implements LatencyStrategy {
        private final long minMillis;
        private final long maxMillis;

        RandomLatencyStrategy(long minMillis, long maxMillis) {
            this.minMillis = minMillis;
            this.maxMillis = maxMillis;
        }

        /**
         * Selects a delay within the configured inclusive range.
         *
         * @return the selected delay in milliseconds
         */
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

        /** {@inheritDoc} */
        @Override public boolean equals(Object other) {
            if (!(other instanceof RandomLatencyStrategy)) return false;
            RandomLatencyStrategy that = (RandomLatencyStrategy) other;
            return minMillis == that.minMillis && maxMillis == that.maxMillis;
        }
        /** {@inheritDoc} */
        @Override public int hashCode() { return 31 * Long.hashCode(minMillis) + Long.hashCode(maxMillis); }
        /**
         * Returns a concise description such as {@code random(50ms,200ms)}.
         *
         * @return the strategy description
         */
        @Override public String toString() { return "random(" + minMillis + "ms," + maxMillis + "ms)"; }
    }
}
