package com.deployhub.platform.worker;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.deployment.DeploymentLogWriter;
import com.deployhub.platform.deployment.DeploymentMetrics;
import com.deployhub.platform.deployment.DeploymentTransitionService;
import com.deployhub.platform.models.entities.Deployment;
import com.deployhub.platform.models.entities.DeploymentJob;
import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.JobStatus;
import com.deployhub.platform.repositories.DeploymentJobRepository;
import com.deployhub.platform.repositories.DeploymentRepository;
import com.deployhub.platform.repositories.ProjectRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs deployment jobs from the durable queue. SIMULATION ONLY: nothing is cloned, built or executed
 * (real builds arrive in Milestone 6), but queueing, claiming, leases, retries and recovery are real.
 *
 * <p>Delivery is at-least-once: if a worker dies after doing a step but before recording it, another
 * worker may repeat that step. Every step is therefore idempotent - it looks at the deployment's
 * current status and continues from there - and the deployment's @Version column stops two workers
 * from both applying the same transition.
 *
 * <p>Simulated failures, for demos and tests (put the word in the project's build command):
 * <ul>
 *   <li>{@code fail} - permanent build error: deployment FAILED immediately, no retry</li>
 *   <li>{@code flaky} - transient error on attempts 1 and 2, succeeds on attempt 3</li>
 *   <li>{@code unstable} - transient error on every attempt: retries run out, job DEAD</li>
 * </ul>
 */
@Slf4j
@Component
public class DeploymentJobWorker {

    /** A job this worker has claimed (holds the lease for). */
    public record ClaimedJob(Long jobId, Long deploymentId, int attempt, int maxAttempts) {
    }

    /** SKIPPED: a newer deployment of the project is already LIVE, so this older one must not replace it. */
    private enum StepOutcome { CONTINUE, DONE, CANCELLED, SKIPPED }

    private final DeploymentJobRepository jobs;
    private final DeploymentRepository deployments;
    private final ProjectRepository projects;
    private final DeploymentTransitionService transitions;
    private final DeploymentLogWriter logWriter;
    private final DeploymentMetrics metrics;
    private final TransactionTemplate tx;
    private final DeployHubProperties.Worker settings;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final String workerId;

    public DeploymentJobWorker(DeploymentJobRepository jobs, DeploymentRepository deployments,
                               ProjectRepository projects, DeploymentTransitionService transitions,
                               DeploymentLogWriter logWriter, DeploymentMetrics metrics,
                               PlatformTransactionManager transactionManager, DeployHubProperties properties,
                               Clock clock) {
        this.jobs = jobs;
        this.deployments = deployments;
        this.projects = projects;
        this.transitions = transitions;
        this.logWriter = logWriter;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(transactionManager);
        this.settings = properties.worker();
        this.retryPolicy = new RetryPolicy(settings.retryBaseDelay(), settings.retryMaxDelay(),
                ThreadLocalRandom.current());
        this.clock = clock;
        this.workerId = settings.id() == null || settings.id().isBlank() ? defaultWorkerId() : settings.id();
    }

    public String workerId() {
        return workerId;
    }

    /** Claims and fully runs due jobs on the calling thread, up to {@code max}. Returns how many ran. */
    public int runAvailableJobs(int max) {
        int ran = 0;
        while (ran < max) {
            Optional<ClaimedJob> job = claimNext();
            if (job.isEmpty()) {
                break;
            }
            execute(job.get());
            ran++;
        }
        return ran;
    }

    /**
     * Atomically claims the oldest due job: PENDING and due, or RUNNING with an expired lease
     * (its worker died). Candidates are read first, then claimed with a conditional UPDATE; if
     * another worker got there first the update changes 0 rows and we try the next candidate.
     */
    public Optional<ClaimedJob> claimNext() {
        Instant now = clock.instant();
        List<Long> candidates = jobs.findClaimableIds(now, Limit.of(10));
        for (Long jobId : candidates) {
            int claimed;
            try {
                claimed = tx.execute(status -> jobs.claim(jobId, workerId, now.plus(settings.leaseDuration()), now));
            } catch (RuntimeException concurrentUpdate) {
                // Some databases report a lost race as an error instead of "0 rows updated".
                log.debug("Lost the race for job {}: {}", jobId, concurrentUpdate.getMessage());
                continue;
            }
            if (claimed == 1) {
                DeploymentJob job = jobs.findById(jobId).orElseThrow();
                metrics.jobClaimed(Duration.between(job.getAvailableAt(), now), job.getAttempt());
                return Optional.of(new ClaimedJob(job.getId(), job.getDeploymentId(), job.getAttempt(),
                        job.getMaxAttempts()));
            }
        }
        return Optional.empty();
    }

    /** Runs a claimed job to the end (or until it fails, is cancelled, or the lease is lost). */
    public void execute(ClaimedJob job) {
        if (job.attempt() > job.maxAttempts()) {
            // Recovered after its worker died, but it has used up its attempts (e.g. it keeps crashing workers).
            handleStepFailure(job, DeploymentStepException.transientError(
                    "Worker stopped responding during the last attempt"));
            return;
        }
        try {
            if (job.attempt() > 1) {
                tx.executeWithoutResult(s -> logWriter.info(job.deploymentId(),
                        "Attempt " + job.attempt() + " of " + job.maxAttempts() + " (worker " + workerId + ")"));
            }
            while (true) {
                if (!heartbeat(job)) {
                    log.warn("Worker {} lost the lease on job {}; another worker took over", workerId, job.jobId());
                    return;
                }
                StepOutcome outcome = tx.execute(s -> advanceOneStep(job));
                if (outcome == StepOutcome.DONE) {
                    finish(job, JobStatus.SUCCEEDED, null);
                    return;
                }
                if (outcome == StepOutcome.CANCELLED) {
                    finish(job, JobStatus.CANCELLED, "Cancelled by user");
                    return;
                }
                if (outcome == StepOutcome.SKIPPED) {
                    finish(job, JobStatus.CANCELLED, "Skipped: a newer deployment is already live");
                    return;
                }
                pause(settings.stepDelay());
            }
        } catch (DeploymentStepException e) {
            handleStepFailure(job, e);
        } catch (OptimisticLockingFailureException e) {
            // Someone (usually the user cancelling) changed the deployment under us. Re-read and decide.
            handleConcurrentChange(job);
        } catch (RuntimeException e) {
            log.error("Unexpected error in job {}", job.jobId(), e);
            handleStepFailure(job, DeploymentStepException.transientError("Unexpected worker error: " + e.getMessage()));
        }
    }

    private StepOutcome advanceOneStep(ClaimedJob job) {
        Deployment deployment = deployments.findById(job.deploymentId()).orElse(null);
        if (deployment == null || deployment.getStatus() == DeploymentStatus.CANCELLED) {
            return StepOutcome.CANCELLED;
        }
        if (!deployment.getStatus().isInProgress()) {
            return StepOutcome.DONE; // already finished (e.g. a previous attempt completed it)
        }
        ProjectMaster project = deployment.getProject();
        Long id = deployment.getId();
        String actor = "worker:" + workerId;

        switch (deployment.getStatus()) {
            case QUEUED -> {
                transitions.transition(deployment, DeploymentStatus.BUILDING, null, actor, job.attempt());
                logWriter.info(id, "[simulated] Worker " + workerId + " picked up the deployment");
                logWriter.info(id, "[simulated] Cloning " + project.getRepositoryUrl() + " @ "
                        + (deployment.getCommitRef() != null ? deployment.getCommitRef() : project.getBranch()));
            }
            case BUILDING -> {
                String build = project.getBuildCommand() == null ? "" : project.getBuildCommand().toLowerCase(Locale.ROOT);
                logWriter.info(id, "[simulated] Running build: " + (project.getBuildCommand() != null ? project.getBuildCommand() : "(none)"));
                if (build.contains("unstable") || (build.contains("flaky") && job.attempt() < 3)) {
                    throw DeploymentStepException.transientError("Simulated transient error: package registry timed out");
                }
                if (build.contains("fail")) {
                    throw DeploymentStepException.permanent("Simulated build failure: build command exited with code 1");
                }
                logWriter.info(id, "[simulated] Build finished");
                transitions.transition(deployment, DeploymentStatus.STARTING, null, actor, job.attempt());
            }
            case STARTING -> {
                // Serialise "go LIVE" per project: without the lock, two deployments finishing at the same
                // moment would each miss the other's uncommitted LIVE row and both stay LIVE.
                projects.lockById(project.getId());
                List<Deployment> live = deployments.findByProjectIdAndStatusAndIdNot(
                        project.getId(), DeploymentStatus.LIVE, id);
                Optional<Deployment> newer = live.stream().filter(d -> d.getId() > id).findFirst();
                if (newer.isPresent()) {
                    // Ids grow with creation order. A slow older deployment (e.g. one that needed retries)
                    // must not replace the newer code that is already live.
                    String reason = "Skipped: newer deployment #" + newer.get().getId() + " is already live";
                    transitions.transition(deployment, DeploymentStatus.CANCELLED, reason, actor, job.attempt());
                    logWriter.warn(id, reason);
                    deployments.flush();
                    metrics.deploymentFinished(DeploymentStatus.CANCELLED, deployment.getCreatedAt());
                    return StepOutcome.SKIPPED;
                }
                logWriter.info(id, "[simulated] Starting: " + project.getStartCommand());
                logWriter.info(id, "[simulated] Health check passed");
                transitions.transition(deployment, DeploymentStatus.LIVE, null, actor, job.attempt());
                for (Deployment previous : live) {
                    transitions.transition(previous, DeploymentStatus.SUPERSEDED, "Replaced by deployment #" + id,
                            actor, null);
                    logWriter.info(previous.getId(), "Superseded by deployment #" + id);
                }
                logWriter.info(id, "[simulated] Deployment is LIVE");
                deployments.flush();
                metrics.deploymentFinished(DeploymentStatus.LIVE, deployment.getCreatedAt());
                return StepOutcome.DONE;
            }
            default -> {
                return StepOutcome.DONE;
            }
        }
        deployments.flush();
        return StepOutcome.CONTINUE;
    }

    private void handleStepFailure(ClaimedJob job, DeploymentStepException e) {
        boolean retriesLeft = e.isRetryable() && job.attempt() < job.maxAttempts();
        int attemptsUsed = Math.min(job.attempt(), job.maxAttempts());
        JobStatus finalStatus = e.isRetryable() ? JobStatus.DEAD : JobStatus.FAILED;
        try {
            // Returns when the failed deployment was requested, if this call is what failed it (for metrics).
            Instant failedRequestedAt = tx.execute(s -> {
                Instant now = clock.instant();
                if (retriesLeft) {
                    Duration wait = retryPolicy.delayAfter(job.attempt());
                    requireLease(jobs.reschedule(job.jobId(), workerId, now.plus(wait), e.getMessage(), now));
                    logWriter.warn(job.deploymentId(), "Attempt " + job.attempt() + " of " + job.maxAttempts()
                            + " failed: " + e.getMessage() + ". Retrying in " + wait.toMillis() + " ms");
                    return null;
                }
                String reason = e.isRetryable()
                        ? "Gave up after " + attemptsUsed + " attempts: " + e.getMessage()
                        : e.getMessage();
                requireLease(jobs.finish(job.jobId(), workerId, finalStatus, e.getMessage(), now));
                Deployment deployment = deployments.findById(job.deploymentId()).orElseThrow();
                if (deployment.getStatus().isInProgress()) {
                    transitions.transition(deployment, DeploymentStatus.FAILED, reason, "worker:" + workerId,
                            job.attempt());
                    logWriter.error(job.deploymentId(), reason);
                    return deployment.getCreatedAt();
                }
                return null;
            });
            if (retriesLeft) {
                metrics.retryScheduled();
            } else {
                metrics.jobFinished(finalStatus);
                if (failedRequestedAt != null) {
                    metrics.deploymentFinished(DeploymentStatus.FAILED, failedRequestedAt);
                }
            }
        } catch (LeaseLostException | OptimisticLockingFailureException lost) {
            log.warn("Could not record failure of job {}: {}", job.jobId(), lost.getMessage());
        }
    }

    private void handleConcurrentChange(ClaimedJob job) {
        try {
            JobStatus finished = tx.execute(s -> {
                Deployment deployment = deployments.findById(job.deploymentId()).orElse(null);
                Instant now = clock.instant();
                if (deployment == null || deployment.getStatus() == DeploymentStatus.CANCELLED) {
                    requireLease(jobs.finish(job.jobId(), workerId, JobStatus.CANCELLED, "Cancelled by user", now));
                    return JobStatus.CANCELLED;
                } else if (!deployment.getStatus().isInProgress()) {
                    requireLease(jobs.finish(job.jobId(), workerId, JobStatus.SUCCEEDED, null, now));
                    return JobStatus.SUCCEEDED;
                }
                // Still in progress: hand it back to the queue immediately.
                requireLease(jobs.reschedule(job.jobId(), workerId, now, "Concurrent modification; requeued", now));
                return null;
            });
            if (finished != null) {
                metrics.jobFinished(finished);
            }
        } catch (LeaseLostException lost) {
            log.warn("Lease on job {} lost while resolving a concurrent change", job.jobId());
        }
    }

    private boolean heartbeat(ClaimedJob job) {
        Instant now = clock.instant();
        Integer updated = tx.execute(s -> jobs.heartbeat(job.jobId(), workerId, now.plus(settings.leaseDuration()), now));
        return updated != null && updated == 1;
    }

    private void finish(ClaimedJob job, JobStatus status, String error) {
        Integer updated = tx.execute(s -> jobs.finish(job.jobId(), workerId, status, error, clock.instant()));
        if (updated == null || updated == 0) {
            log.warn("Job {} finished but its lease had already been taken over", job.jobId());
        } else {
            metrics.jobFinished(status);
        }
    }

    private static void requireLease(int updatedRows) {
        if (updatedRows != 1) {
            throw new LeaseLostException();
        }
    }

    private static void pause(Duration delay) {
        if (delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Worker interrupted", e);
        }
    }

    private static String defaultWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "worker";
        }
        String shortHost = host.length() > 40 ? host.substring(0, 40) : host;
        return shortHost + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Thrown inside a transaction to roll it back when this worker no longer owns the job. */
    static class LeaseLostException extends RuntimeException {
        LeaseLostException() {
            super("Lease lost");
        }
    }
}
