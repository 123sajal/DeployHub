package com.deployhub.platform.worker;

import com.deployhub.platform.config.DeployHubProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls the queue and runs up to {@code max-concurrent-jobs} jobs at once on a fixed thread pool.
 * It only claims a job when a thread is free, so the backlog stays in the database (bounded memory)
 * instead of piling up in an in-process queue. Tests switch this off and drive the worker directly.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "deployhub.worker.enabled", havingValue = "true")
public class DeploymentJobScheduler implements DisposableBean {

    private final DeploymentJobWorker worker;
    private final int maxConcurrent;
    private final ExecutorService executor;
    private final AtomicInteger running = new AtomicInteger();
    private final Clock clock;
    private volatile Instant lastSuccessfulPoll;
    private volatile boolean healthy = true;

    public DeploymentJobScheduler(DeploymentJobWorker worker, DeployHubProperties properties, Clock clock) {
        this.worker = worker;
        this.clock = clock;
        this.lastSuccessfulPoll = clock.instant(); // counts as alive until the first poll is due
        this.maxConcurrent = properties.worker().maxConcurrentJobs();
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(maxConcurrent,
                runnable -> {
                    Thread thread = new Thread(runnable, "deploy-worker-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        log.info("Deployment worker {} started with {} concurrent job slot(s)", worker.workerId(), maxConcurrent);
    }

    @Scheduled(fixedDelayString = "${deployhub.worker.poll-interval}")
    public void poll() {
        if (running.get() >= maxConcurrent) {
            lastSuccessfulPoll = clock.instant(); // all slots busy: nothing to claim, but the poller is alive
            return;
        }
        while (running.get() < maxConcurrent) {
            Optional<DeploymentJobWorker.ClaimedJob> job;
            try {
                job = worker.claimNext();
            } catch (RuntimeException e) {
                // Usually the database is down. Log once per outage, not once per second; readiness reports it.
                if (healthy) {
                    log.warn("Worker {} cannot reach the queue: {}", worker.workerId(), e.getMessage());
                    healthy = false;
                }
                return;
            }
            if (!healthy) {
                log.info("Worker {} can reach the queue again", worker.workerId());
                healthy = true;
            }
            lastSuccessfulPoll = clock.instant();
            if (job.isEmpty()) {
                return;
            }
            running.incrementAndGet();
            executor.submit(() -> {
                try {
                    worker.execute(job.get());
                } finally {
                    running.decrementAndGet();
                }
            });
        }
    }

    /** When this worker last read the queue successfully (with all slots busy it doesn't read, but is alive). */
    public Instant lastSuccessfulPoll() {
        return lastSuccessfulPoll;
    }

    public int runningJobs() {
        return running.get();
    }

    /** In-flight jobs are simply abandoned; their leases expire and another worker resumes them. */
    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
