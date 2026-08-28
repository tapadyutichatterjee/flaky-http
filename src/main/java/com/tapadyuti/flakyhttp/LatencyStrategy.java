package com.tapadyuti.flakyhttp;

import java.util.Random;
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
        if (minMillis > maxMillis) {
            throw new IllegalArgumentException("minMillis must be less than or equal to maxMillis");
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
            return ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1);
        }
    }
}
