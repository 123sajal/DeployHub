package com.deployhub.platform.worker;

import com.deployhub.platform.config.DeployHubProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The "worker" part of /actuator/health/readiness. DOWN when this process's worker has not read the queue
 * for a while (database unreachable, or the polling thread is stuck), so a load balancer or orchestrator
 * stops counting on it. Not part of liveness: a restart would not fix an unreachable database.
 * Processes with the worker switched off (API-only) always report UP.
 */
@Component
public class WorkerHealthIndicator implements HealthIndicator {

    private static final Duration MIN_STALE_AFTER = Duration.ofSeconds(30);

    private final ObjectProvider<DeploymentJobScheduler> scheduler;
    private final Duration staleAfter;
    private final Clock clock;

    public WorkerHealthIndicator(ObjectProvider<DeploymentJobScheduler> scheduler, DeployHubProperties properties,
                                 Clock clock) {
        this.scheduler = scheduler;
        Duration tenPolls = properties.worker().pollInterval().multipliedBy(10);
        this.staleAfter = tenPolls.compareTo(MIN_STALE_AFTER) > 0 ? tenPolls : MIN_STALE_AFTER;
        this.clock = clock;
    }

    @Override
    public Health health() {
        DeploymentJobScheduler worker = scheduler.getIfAvailable();
        if (worker == null) {
            return Health.up().withDetail("enabled", false).build();
        }
        Instant last = worker.lastSuccessfulPoll();
        Duration since = Duration.between(last, clock.instant());
        Health.Builder health = since.compareTo(staleAfter) > 0 ? Health.down() : Health.up();
        return health.withDetail("enabled", true)
                .withDetail("lastSuccessfulPoll", last.toString())
                .withDetail("runningJobs", worker.runningJobs())
                .build();
    }
}
