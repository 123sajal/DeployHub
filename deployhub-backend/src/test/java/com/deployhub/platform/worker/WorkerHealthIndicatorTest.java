package com.deployhub.platform.worker;

import com.deployhub.platform.config.DeployHubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.health.contributor.Status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static DeployHubProperties properties(Duration pollInterval) {
        return new DeployHubProperties(new DeployHubProperties.Worker(true, "w", pollInterval, 2, Duration.ofSeconds(30),
                Duration.ZERO, 3, Duration.ZERO, Duration.ZERO), null, null, null, null);
    }

    private static WorkerHealthIndicator indicator(DeploymentJobScheduler scheduler, Duration pollInterval) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (scheduler != null) {
            beans.registerSingleton("scheduler", scheduler);
        }
        return new WorkerHealthIndicator(beans.getBeanProvider(DeploymentJobScheduler.class),
                properties(pollInterval), CLOCK);
    }

    private static DeploymentJobScheduler polledAgo(Duration ago) {
        DeploymentJobScheduler scheduler = mock(DeploymentJobScheduler.class);
        when(scheduler.lastSuccessfulPoll()).thenReturn(NOW.minus(ago));
        return scheduler;
    }

    @Test
    void upWhenThisProcessRunsNoWorker() {
        assertThat(indicator(null, Duration.ofSeconds(1)).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void upWhileTheWorkerPollsRegularly() {
        assertThat(indicator(polledAgo(Duration.ofSeconds(2)), Duration.ofSeconds(1)).health().getStatus())
                .isEqualTo(Status.UP);
    }

    @Test
    void downWhenTheWorkerHasNotReadTheQueueForAWhile() {
        // 1 s polling: stale after the 30 s minimum, not after 10 polls (10 s), so short hiccups don't flap.
        assertThat(indicator(polledAgo(Duration.ofSeconds(20)), Duration.ofSeconds(1)).health().getStatus())
                .isEqualTo(Status.UP);
        assertThat(indicator(polledAgo(Duration.ofSeconds(31)), Duration.ofSeconds(1)).health().getStatus())
                .isEqualTo(Status.DOWN);
        // Slow polling (10 s): stale only after 10 missed polls.
        assertThat(indicator(polledAgo(Duration.ofSeconds(90)), Duration.ofSeconds(10)).health().getStatus())
                .isEqualTo(Status.UP);
        assertThat(indicator(polledAgo(Duration.ofSeconds(101)), Duration.ofSeconds(10)).health().getStatus())
                .isEqualTo(Status.DOWN);
    }
}
