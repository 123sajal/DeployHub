package com.deployhub.platform.deployment;

import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.JobStatus;
import com.deployhub.platform.repositories.DeploymentJobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics for the deployment pipeline, scraped from /actuator/prometheus:
 * <ul>
 *   <li>{@code deployhub.queue.jobs{state}} - jobs waiting, scheduled for a retry, running, or DEAD.
 *       Read from the database, so every process reports the same platform-wide numbers.</li>
 *   <li>{@code deployhub.queue.wait{attempt}} - how long a job waited before a worker claimed it.</li>
 *   <li>{@code deployhub.jobs.finished{outcome}} and {@code deployhub.jobs.retries} - throughput is the rate of these.</li>
 *   <li>{@code deployhub.deployments.duration{status}} - request to final state (LIVE, FAILED, CANCELLED).</li>
 * </ul>
 * Counters and timers are per process and restart from zero; Prometheus sums them across processes.
 */
@Slf4j
@Component
public class DeploymentMetrics {

    private final MeterRegistry registry;
    private final DeploymentJobRepository jobs;
    private final Clock clock;
    private final AtomicLong waiting = new AtomicLong();
    private final AtomicLong retryScheduled = new AtomicLong();
    private final AtomicLong running = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();

    public DeploymentMetrics(MeterRegistry registry, DeploymentJobRepository jobs, Clock clock) {
        this.registry = registry;
        this.jobs = jobs;
        this.clock = clock;
        gauge("waiting", waiting, "PENDING jobs that are due and wait for a free worker");
        gauge("retry_scheduled", retryScheduled, "PENDING jobs waiting for their retry time");
        gauge("running", running, "Jobs a worker currently holds");
        gauge("dead", dead, "Jobs that ran out of retries (dead letter)");
    }

    private void gauge(String state, AtomicLong value, String description) {
        Gauge.builder("deployhub.queue.jobs", value, AtomicLong::get)
                .tag("state", state)
                .description(description)
                .register(registry);
    }

    /**
     * Re-reads the queue depth. Cached rather than queried on every scrape, so a scrape never waits for the
     * database and a database outage can't make the metrics endpoint fail (the gauges keep their last value).
     */
    @Scheduled(fixedDelayString = "${deployhub.metrics.queue-refresh-interval}")
    public void refreshQueueDepth() {
        try {
            Instant now = clock.instant();
            waiting.set(jobs.countByStatusAndAvailableAtLessThanEqual(JobStatus.PENDING, now));
            retryScheduled.set(jobs.countByStatusAndAvailableAtGreaterThan(JobStatus.PENDING, now));
            running.set(jobs.countByStatus(JobStatus.RUNNING));
            dead.set(jobs.countByStatus(JobStatus.DEAD));
        } catch (RuntimeException e) {
            log.debug("Could not refresh queue metrics: {}", e.getMessage());
        }
    }

    public void jobClaimed(Duration waited, int attempt) {
        Timer.builder("deployhub.queue.wait")
                .tag("attempt", attempt > 1 ? "retry" : "first")
                .description("Time a job waited in the queue before a worker claimed it")
                .publishPercentileHistogram()
                .register(registry)
                .record(waited.isNegative() ? Duration.ZERO : waited);
    }

    public void retryScheduled() {
        Counter.builder("deployhub.jobs.retries").description("Failed attempts that were rescheduled")
                .register(registry).increment();
    }

    public void jobFinished(JobStatus outcome) {
        Counter.builder("deployhub.jobs.finished")
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .description("Jobs that reached a final state")
                .register(registry)
                .increment();
    }

    public void deploymentFinished(DeploymentStatus status, Instant requestedAt) {
        Timer.builder("deployhub.deployments.duration")
                .tag("status", status.name().toLowerCase(Locale.ROOT))
                .description("Time from the deployment request to its final state")
                .register(registry)
                .record(Duration.between(requestedAt, clock.instant()));
    }
}
