package com.deployhub.platform.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(60), new Random(42));

    @Test
    void delayDoublesEachAttemptWithJitterInTheUpperHalf() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            long ceiling = 2000L << (attempt - 1); // 2s, 4s, 8s, 16s, 32s
            for (int i = 0; i < 200; i++) {
                assertThat(policy.delayAfter(attempt).toMillis())
                        .as("attempt %d", attempt)
                        .isBetween(ceiling / 2, ceiling);
            }
        }
    }

    @Test
    void delayIsCappedAndNeverOverflows() {
        assertThat(policy.delayAfter(10).toMillis()).isBetween(30_000L, 60_000L);
        assertThat(policy.delayAfter(1_000).toMillis()).isBetween(30_000L, 60_000L);
    }

    @Test
    void jitterSpreadsRetriesOut() {
        long distinct = java.util.stream.IntStream.range(0, 50)
                .mapToLong(i -> policy.delayAfter(3).toMillis()).distinct().count();
        assertThat(distinct).isGreaterThan(40);
    }

    @Test
    void zeroBaseMeansRetryImmediately() {
        RetryPolicy immediate = new RetryPolicy(Duration.ZERO, Duration.ofSeconds(60), new Random());
        assertThat(immediate.delayAfter(3)).isZero();
    }
}
