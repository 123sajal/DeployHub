package com.deployhub.platform.worker;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with "equal jitter": the n-th retry waits between half and all of
 * base * 2^(n-1), capped. The randomness spreads out retries so that many jobs failing at the
 * same moment (e.g. a registry outage) don't all hit the dependency again at the same moment.
 */
public final class RetryPolicy {

    private final Duration baseDelay;
    private final Duration maxDelay;
    private final RandomGenerator random;

    public RetryPolicy(Duration baseDelay, Duration maxDelay, RandomGenerator random) {
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.random = random;
    }

    /** @param failedAttempt the attempt that just failed (1 = first try) */
    public Duration delayAfter(int failedAttempt) {
        long baseMillis = baseDelay.toMillis();
        if (baseMillis <= 0) {
            return Duration.ZERO;
        }
        int exponent = Math.min(failedAttempt - 1, 30); // avoid overflow
        long exponential = Math.min(maxDelay.toMillis(), baseMillis * (1L << exponent));
        long half = exponential / 2;
        return Duration.ofMillis(half + (long) (random.nextDouble() * (exponential - half)));
    }
}
