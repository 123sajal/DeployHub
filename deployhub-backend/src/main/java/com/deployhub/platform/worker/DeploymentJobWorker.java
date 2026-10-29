package com.deployhub.platform.worker;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.deployment.DeploymentLogWriter;
import com.deployhub.platform.deployment.DeploymentMetrics;
import com.deployhub.platform.deployment.ProjectGateways;
import com.deployhub.platform.deployment.DeploymentTransitionService;
import com.deployhub.platform.execution.DeploymentExecutor;
import com.deployhub.platform.execution.DeploymentExecutors;
import com.deployhub.platform.execution.DeploymentSpec;
import com.deployhub.platform.execution.ExecutionContext;
import com.deployhub.platform.execution.StepCancelledException;
import com.deployhub.platform.mappers.ProjectMapper;
import com.deployhub.platform.models.entities.Deployment;
import com.deployhub.platform.models.entities.DeploymentJob;
import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.models.enums.JobStatus;
import com.deployhub.platform.repositories.DeploymentJobRepository;
import com.deployhub.platform.repositories.DeploymentRepository;
import com.deployhub.platform.repositories.ProjectEnvVarRepository;
import com.deployhub.platform.repositories.ProjectRepository;
import com.deployhub.platform.security.SecretCipher;
import jakarta.annotation.PreDestroy;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Runs deployment jobs from the durable queue: claims a job, then moves its deployment through
 * QUEUED -> BUILDING -> STARTING -> LIVE. The actual building and running is done by a
 * {@link DeploymentExecutor}: Docker for REAL deployments, the simulator for SIMULATED ones.
 *
 * <p><b>Short transactions, long work.</b> A real build can take minutes, but the connection pool is
 * tiny. Each step therefore reads what it needs in one short transaction, does the work outside any
 * transaction, and records the result in another short transaction that re-checks the deployment
 * (a user may have cancelled meanwhile; the {@code @Version} column catches a concurrent change).
 * While the work runs, a guard renews the job's lease every couple of seconds and stops the work if
 * the user cancels or the lease is lost.
 *
 * <p><b>At-least-once.</b> If a worker dies mid-step, another worker repeats that step. Steps are written
 * to be repeatable: each continues from the deployment's current status, and executors clean up after
 * an interrupted earlier attempt.
 */
@Slf4j
@Component
public class DeploymentJobWorker {

    /** A job this worker has claimed (holds the lease for). */
    public record ClaimedJob(Long jobId, Long deploymentId, int attempt, int maxAttempts) {
    }

    /**
     * SKIPPED: a newer deployment of the project is already LIVE, so this older one must not replace it.
     * LEASE_LOST: another worker took the job over; stop without writing anything.
     */
    private enum StepOutcome { CONTINUE, DONE, CANCELLED, SKIPPED, LEASE_LOST }

    /** Result of the final "go live" transaction; containers and images are cleaned up after it commits. */
    private record GoLive(StepOutcome outcome, List<String> supersededContainers, Set<String> keepImages) {

        static GoLive of(StepOutcome outcome) {
            return new GoLive(outcome, List.of(), Set.of());
        }
    }

    private final DeploymentJobRepository jobs;
    private final DeploymentRepository deployments;
    private final ProjectRepository projects;
    private final DeploymentTransitionService transitions;
    private final DeploymentLogWriter logWriter;
    private final DeploymentMetrics metrics;
    private final DeploymentExecutors executors;
    private final ProjectGateways gateways;
    private final ProjectEnvVarRepository envVars;
    private final SecretCipher cipher;
    private final TransactionTemplate tx;
    private final DeployHubProperties.Worker settings;
    private final DeployHubProperties.Execution execution;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final String workerId;
    private final ScheduledExecutorService guards;

    public DeploymentJobWorker(DeploymentJobRepository jobs, DeploymentRepository deployments,
                               ProjectRepository projects, DeploymentTransitionService transitions,
                               DeploymentLogWriter logWriter, DeploymentMetrics metrics, DeploymentExecutors executors,
                               ProjectGateways gateways, ProjectEnvVarRepository envVars, SecretCipher cipher,
                               PlatformTransactionManager transactionManager, DeployHubProperties properties,
                               Clock clock) {
        this.jobs = jobs;
        this.deployments = deployments;
        this.projects = projects;
        this.transitions = transitions;
        this.logWriter = logWriter;
        this.metrics = metrics;
        this.executors = executors;
        this.gateways = gateways;
        this.envVars = envVars;
        this.cipher = cipher;
        this.tx = new TransactionTemplate(transactionManager);
        this.settings = properties.worker();
        this.execution = properties.execution();
        this.retryPolicy = new RetryPolicy(settings.retryBaseDelay(), settings.retryMaxDelay(),
                ThreadLocalRandom.current());
        this.clock = clock;
        this.workerId = settings.id() == null || settings.id().isBlank() ? defaultWorkerId() : settings.id();
        AtomicInteger threadNumber = new AtomicInteger();
        this.guards = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "deploy-guard-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
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
                logWriter.info(job.deploymentId(),
                        "Attempt " + job.attempt() + " of " + job.maxAttempts() + " (worker " + workerId + ")");
            }
            while (true) {
                if (!heartbeat(job)) {
                    log.warn("Worker {} lost the lease on job {}; another worker took over", workerId, job.jobId());
                    return;
                }
                switch (advanceOneStep(job)) {
                    case DONE -> {
                        finish(job, JobStatus.SUCCEEDED, null);
                        return;
                    }
                    case CANCELLED -> {
                        finish(job, JobStatus.CANCELLED, "Cancelled by user");
                        return;
                    }
                    case SKIPPED -> {
                        finish(job, JobStatus.CANCELLED, "Skipped: a newer deployment is already live");
                        return;
                    }
                    case LEASE_LOST -> {
                        log.warn("Worker {} lost the lease on job {} during a step", workerId, job.jobId());
                        return;
                    }
                    case CONTINUE -> {
                        // next step
                    }
                }
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
        DeploymentSpec spec = tx.execute(s -> loadSpec(job));
        if (spec == null || spec.status() == DeploymentStatus.CANCELLED) {
            return StepOutcome.CANCELLED;
        }
        if (!spec.status().isInProgress()) {
            return StepOutcome.DONE; // already finished (e.g. a previous attempt completed it)
        }
        DeploymentExecutor executor = executors.forMode(spec.mode());
        String actor = "worker:" + workerId;
        try {
            return switch (spec.status()) {
                case QUEUED -> pickUp(job, spec, actor);
                case BUILDING -> build(job, spec, executor, actor);
                case STARTING -> start(job, spec, executor, actor);
                default -> StepOutcome.DONE;
            };
        } catch (StepCancelledException stopped) {
            return stopped.getReason() == ExecutionContext.CancelReason.LEASE_LOST
                    ? StepOutcome.LEASE_LOST : StepOutcome.CANCELLED;
        }
    }

    private StepOutcome pickUp(ClaimedJob job, DeploymentSpec spec, String actor) {
        return tx.execute(s -> {
            Deployment deployment = current(spec, DeploymentStatus.QUEUED);
            if (deployment == null) {
                return StepOutcome.CONTINUE; // changed meanwhile (e.g. cancelled): the next loop re-reads it
            }
            transitions.transition(deployment, DeploymentStatus.BUILDING, null, actor, job.attempt());
            logWriter.info(deployment.getId(), prefix(spec) + "Worker " + workerId + " picked up the deployment");
            deployments.flush();
            return StepOutcome.CONTINUE;
        });
    }

    private StepOutcome build(ClaimedJob job, DeploymentSpec spec, DeploymentExecutor executor, String actor) {
        DeploymentExecutor.BuildResult built = guarded(job, spec, context -> executor.build(spec, context));
        return tx.execute(s -> {
            Deployment deployment = current(spec, DeploymentStatus.BUILDING);
            if (deployment == null) {
                return StepOutcome.CONTINUE;
            }
            deployment.recordBuild(built.resolvedCommit(), built.imageRef());
            transitions.transition(deployment, DeploymentStatus.STARTING, null, actor, job.attempt());
            deployments.flush();
            return StepOutcome.CONTINUE;
        });
    }

    private StepOutcome start(ClaimedJob job, DeploymentSpec spec, DeploymentExecutor executor, String actor) {
        // Don't even start an old version when newer code is already live.
        StepOutcome early = tx.execute(s -> {
            projects.lockById(spec.projectId());
            Deployment deployment = current(spec, DeploymentStatus.STARTING);
            if (deployment == null) {
                return StepOutcome.CONTINUE;
            }
            return newerLive(spec).map(newer -> skip(job, deployment, newer, actor)).orElse(null);
        });
        if (early != null) {
            return early;
        }

        DeploymentExecutor.RunResult run = guarded(job, spec, context -> executor.start(spec, context));
        GoLive result;
        try {
            result = tx.execute(s -> goLive(job, spec, run, actor));
        } catch (RuntimeException e) {
            executor.stop(run.containerName()); // never leave a container running that the database doesn't know
            throw e;
        }
        switch (result.outcome()) {
            case DONE -> {
                if (spec.mode() == ExecutionMode.REAL) {
                    // Switch the fixed address over before the old container is stopped.
                    updateAddress(spec);
                }
                result.supersededContainers().forEach(executor::stop);
                if (!result.keepImages().isEmpty()) {
                    executor.pruneImages(spec.projectId(), result.keepImages());
                }
            }
            case CANCELLED, SKIPPED -> executor.stop(run.containerName());
            default -> {
                // status moved on elsewhere; the next loop re-reads it
            }
        }
        return result.outcome();
    }

    /**
     * Serialised per project by the row lock: only one deployment of a project switches to LIVE at a time,
     * across all workers, and an older deployment never replaces a newer live one.
     */
    private GoLive goLive(ClaimedJob job, DeploymentSpec spec, DeploymentExecutor.RunResult run, String actor) {
        projects.lockById(spec.projectId());
        Deployment deployment = deployments.findById(spec.deploymentId()).orElse(null);
        if (deployment == null || deployment.getStatus() == DeploymentStatus.CANCELLED) {
            return GoLive.of(StepOutcome.CANCELLED);
        }
        if (deployment.getStatus() != DeploymentStatus.STARTING) {
            return GoLive.of(StepOutcome.CONTINUE);
        }
        Optional<Deployment> newer = newerLive(spec);
        if (newer.isPresent()) {
            return GoLive.of(skip(job, deployment, newer.get(), actor));
        }
        Long id = deployment.getId();
        deployment.recordRun(run.containerName(), run.hostPort(), run.hostPort() == null ? null : spec.appPort());
        transitions.transition(deployment, DeploymentStatus.LIVE, null, actor, job.attempt());
        List<String> superseded = new ArrayList<>();
        for (Deployment previous : deployments.findByProjectIdAndStatusAndIdNot(spec.projectId(),
                DeploymentStatus.LIVE, id)) {
            transitions.transition(previous, DeploymentStatus.SUPERSEDED, "Replaced by deployment #" + id, actor, null);
            logWriter.info(previous.getId(), "Superseded by deployment #" + id);
            if (previous.getContainerName() != null) {
                superseded.add(previous.getContainerName());
            }
        }
        logWriter.info(id, run.hostPort() != null
                ? "Deployment is LIVE (this deployment directly: http://localhost:" + run.hostPort() + ")"
                : prefix(spec) + "Deployment is LIVE");
        deployments.flush();
        metrics.deploymentFinished(DeploymentStatus.LIVE, deployment.getCreatedAt());
        Set<String> keep = new HashSet<>(deployments.findRecentImageRefs(spec.projectId(),
                EnumSet.of(DeploymentStatus.LIVE, DeploymentStatus.SUPERSEDED),
                Limit.of(Math.max(1, execution.keepImagesPerProject()))));
        return new GoLive(StepOutcome.DONE, superseded, keep);
    }

    /** A failure here doesn't undo the go-live: the app runs and is reachable on its own port. */
    private void updateAddress(DeploymentSpec spec) {
        try {
            gateways.refreshAffectedBy(spec.projectId());
            tx.executeWithoutResult(s -> projects.findById(spec.projectId())
                    .filter(p -> p.getPublicPort() != null)
                    .ifPresent(p -> logWriter.info(spec.deploymentId(), "Project address http://localhost:"
                            + p.getPublicPort() + " now serves this deployment")));
        } catch (RuntimeException e) {
            log.warn("Could not update the address of project {}", spec.projectId(), e);
            logWriter.warn(spec.deploymentId(), "Could not update the project's address: " + e.getMessage());
        }
    }

    /** Ids grow with creation order, so "newer" means a higher id. */
    private Optional<Deployment> newerLive(DeploymentSpec spec) {
        return deployments.findByProjectIdAndStatusAndIdNot(spec.projectId(), DeploymentStatus.LIVE,
                        spec.deploymentId())
                .stream().filter(d -> d.getId() > spec.deploymentId()).findFirst();
    }

    /** A slow older deployment (e.g. one that needed retries) must not replace newer code that is already live. */
    private StepOutcome skip(ClaimedJob job, Deployment deployment, Deployment newer, String actor) {
        String reason = "Skipped: newer deployment #" + newer.getId() + " is already live";
        transitions.transition(deployment, DeploymentStatus.CANCELLED, reason, actor, job.attempt());
        logWriter.warn(deployment.getId(), reason);
        deployments.flush();
        metrics.deploymentFinished(DeploymentStatus.CANCELLED, deployment.getCreatedAt());
        return StepOutcome.SKIPPED;
    }

    /**
     * Runs a long step outside any transaction. Meanwhile a guard renews the lease and checks for a user
     * cancel every couple of seconds; either one cancels the context, which kills the running process.
     */
    private <T> T guarded(ClaimedJob job, DeploymentSpec spec, Function<ExecutionContext, T> work) {
        ExecutionContext context = new ExecutionContext(job.deploymentId(), logWriter,
                execution.maxLogLinesPerStep());
        context.maskSecrets(spec.environment().values());
        long period = Math.max(100, Math.min(settings.leaseDuration().toMillis() / 3, 2000));
        ScheduledFuture<?> guard = guards.scheduleWithFixedDelay(() -> {
            try {
                if (!heartbeat(job)) {
                    context.cancel(ExecutionContext.CancelReason.LEASE_LOST);
                    return;
                }
                DeploymentStatus status = deployments.findStatusById(job.deploymentId()).orElse(null);
                if (status == null || status == DeploymentStatus.CANCELLED) {
                    context.cancel(ExecutionContext.CancelReason.CANCELLED_BY_USER);
                }
            } catch (RuntimeException e) {
                // A database blip: the lease has slack until the next check.
                log.debug("Guard check for job {} failed: {}", job.jobId(), e.getMessage());
            }
        }, period, period, TimeUnit.MILLISECONDS);
        try {
            return work.apply(context);
        } finally {
            guard.cancel(false);
        }
    }

    /** Re-reads the deployment inside the current transaction; null if it is no longer in {@code expected}. */
    private Deployment current(DeploymentSpec spec, DeploymentStatus expected) {
        return deployments.findById(spec.deploymentId()).filter(d -> d.getStatus() == expected).orElse(null);
    }

    private DeploymentSpec loadSpec(ClaimedJob job) {
        return deployments.findById(job.deploymentId()).map(d -> {
            ProjectMaster p = d.getProject();
            return new DeploymentSpec(d.getId(), d.getStatus(), d.getExecutionMode(), job.attempt(), p.getId(),
                    p.getName(), p.getRepositoryUrl(), d.getCommitRef() != null ? d.getCommitRef() : p.getBranch(),
                    p.getAppType(), p.getBuildCommand(), p.getStartCommand(), ProjectMapper.effectivePort(p),
                    p.getHealthCheckPath(), d.getResolvedCommit(), d.getImageRef(), environment(p.getId()),
                    p.getRootDirectory(), repositoryToken(p));
        }).orElse(null);
    }

    /** The project's access token for a private repository, decrypted; null for a public one. */
    private String repositoryToken(ProjectMaster project) {
        if (project.getRepoTokenEncrypted() == null) {
            return null;
        }
        try {
            return cipher.decrypt(project.getRepoTokenEncrypted());
        } catch (IllegalStateException e) {
            throw DeploymentStepException.permanent("The saved access token can't be read (was DEPLOYHUB_SECRETS_KEY "
                    + "changed?). Paste the token again on the project's Edit page (Private repository), then deploy.");
        }
    }

    /** The project's environment variables, decrypted (current values: changes apply to the next deployment). */
    private Map<String, String> environment(Long projectId) {
        Map<String, String> values = new TreeMap<>();
        for (var variable : envVars.findByProjectIdOrderByNameAsc(projectId)) {
            try {
                values.put(variable.getName(), cipher.decrypt(variable.getValueEncrypted()));
            } catch (IllegalStateException e) {
                throw DeploymentStepException.permanent("Could not read environment variable " + variable.getName()
                        + ": " + e.getMessage());
            }
        }
        return values;
    }

    private static String prefix(DeploymentSpec spec) {
        return spec.mode() == ExecutionMode.SIMULATED ? "[simulated] " : "";
    }

    @PreDestroy
    void shutdown() {
        guards.shutdownNow();
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
