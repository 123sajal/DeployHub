package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.deployment.DeploymentLogWriter;
import com.deployhub.platform.deployment.DeploymentMetrics;
import com.deployhub.platform.deployment.DeploymentTransitionService;
import com.deployhub.platform.exceptions.ConflictException;
import com.deployhub.platform.execution.DeploymentExecutor;
import com.deployhub.platform.execution.DeploymentExecutors;
import com.deployhub.platform.exceptions.FieldValidationException;
import com.deployhub.platform.exceptions.IdempotencyKeyReuseException;
import com.deployhub.platform.exceptions.ResourceNotFoundException;
import com.deployhub.platform.exceptions.TooManyRequestsException;
import com.deployhub.platform.mappers.DeploymentMapper;
import com.deployhub.platform.models.DTO.DeploymentCreationResult;
import com.deployhub.platform.models.DTO.DeploymentHistoryResponseDTO;
import com.deployhub.platform.models.DTO.DeploymentLogDTO;
import com.deployhub.platform.models.DTO.DeploymentRequestDTO;
import com.deployhub.platform.models.DTO.DeploymentResponseDTO;
import com.deployhub.platform.models.DTO.LogPageResponseDTO;
import com.deployhub.platform.models.DTO.PageResponseDTO;
import com.deployhub.platform.models.entities.Deployment;
import com.deployhub.platform.models.entities.DeploymentJob;
import com.deployhub.platform.models.entities.DeploymentLog;
import com.deployhub.platform.models.entities.IdempotencyRecord;
import com.deployhub.platform.models.entities.Payment;
import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.models.enums.JobStatus;
import com.deployhub.platform.repositories.DeploymentJobRepository;
import com.deployhub.platform.repositories.DeploymentLogRepository;
import com.deployhub.platform.repositories.DeploymentRepository;
import com.deployhub.platform.repositories.DeploymentStatusHistoryRepository;
import com.deployhub.platform.repositories.IdempotencyRecordRepository;
import com.deployhub.platform.repositories.ProjectRepository;
import com.deployhub.platform.services.Interface.IAuditService;
import com.deployhub.platform.services.Interface.IBillingService;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IDeploymentService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

@Service
public class DeploymentService implements IDeploymentService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");
    private static final EnumSet<DeploymentStatus> ACTIVE =
            EnumSet.of(DeploymentStatus.QUEUED, DeploymentStatus.BUILDING, DeploymentStatus.STARTING);

    private final DeploymentRepository deploymentRepository;
    private final DeploymentLogRepository deploymentLogRepository;
    private final DeploymentJobRepository jobRepository;
    private final DeploymentStatusHistoryRepository historyRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final ProjectRepository projectRepository;
    private final DeploymentTransitionService transitions;
    private final DeploymentLogWriter logWriter;
    private final ICurrentUserService currentUserService;
    private final IAuditService auditService;
    private final DeploymentMetrics metrics;
    private final DeploymentExecutors executors;
    private final TransactionTemplate tx;
    private final DeployHubProperties properties;
    private final IBillingService billingService;
    private final Clock clock;

    public DeploymentService(DeploymentRepository deploymentRepository, DeploymentLogRepository deploymentLogRepository,
                             DeploymentJobRepository jobRepository, DeploymentStatusHistoryRepository historyRepository,
                             IdempotencyRecordRepository idempotencyRepository, ProjectRepository projectRepository,
                             DeploymentTransitionService transitions, DeploymentLogWriter logWriter,
                             ICurrentUserService currentUserService, IAuditService auditService,
                             DeploymentMetrics metrics, DeploymentExecutors executors,
                             PlatformTransactionManager transactionManager, DeployHubProperties properties,
                             IBillingService billingService, Clock clock) {
        this.deploymentRepository = deploymentRepository;
        this.deploymentLogRepository = deploymentLogRepository;
        this.jobRepository = jobRepository;
        this.historyRepository = historyRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.projectRepository = projectRepository;
        this.transitions = transitions;
        this.logWriter = logWriter;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.executors = executors;
        this.tx = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.billingService = billingService;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponseDTO<DeploymentResponseDTO> list(Long projectId, DeploymentStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponseDTO.from(
                deploymentRepository.search(currentUserService.currentUserId(), projectId, status, pageable),
                DeploymentMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public DeploymentResponseDTO get(Long id) {
        return DeploymentMapper.toResponse(findOwned(id));
    }

    /** What to deploy: worked out inside the creating transaction, after the replay check. */
    private record NewDeployment(ProjectMaster project, String commitRef, Deployment rollbackOf) {
    }

    @Override
    public DeploymentCreationResult create(Long projectId, DeploymentRequestDTO request, String idempotencyKey) {
        String commitRef = request == null ? null : request.commitRef();
        return enqueue(idempotencyKey, requestHash(projectId + "|" + (commitRef == null ? "" : commitRef)),
                userId -> new NewDeployment(projectRepository.findByIdAndOwnerId(projectId, userId)
                        .orElseThrow(() -> new ResourceNotFoundException("Project", projectId)), commitRef, null));
    }

    /**
     * A rollback is a normal new deployment (same queue, admission limit and Idempotency-Key handling) that
     * redeploys an earlier deployment's commit. Being the newest deployment, it wins the "only the newest
     * goes LIVE" rule against anything older still in progress.
     */
    @Override
    public DeploymentCreationResult rollback(Long deploymentId, String idempotencyKey) {
        return enqueue(idempotencyKey, requestHash("rollback|" + deploymentId), userId -> {
            Deployment source = deploymentRepository.findOwned(deploymentId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Deployment", deploymentId));
            if (!source.canBeRolledBackTo()) {
                throw new ConflictException(source.getStatus() == DeploymentStatus.LIVE
                        ? "Deployment #" + deploymentId + " is already live"
                        : "Only a deployment that was live before can be rolled back to (this one is "
                        + source.getStatus() + ")");
            }
            // The exact commit that was built, when known; otherwise what was asked for back then.
            String commit = source.getResolvedCommit() != null ? source.getResolvedCommit() : source.getCommitRef();
            return new NewDeployment(source.getProject(), commit, source);
        });
    }

    /**
     * Idempotency: the (user, key) primary key in idempotency_keys is the real guard. Two identical
     * requests arriving at the same moment both try to insert it; the database lets exactly one
     * commit, and the other one rolls back and returns the winner's deployment.
     */
    private DeploymentCreationResult enqueue(String idempotencyKey, String requestHash,
                                             Function<Long, NewDeployment> plan) {
        Long userId = currentUserService.currentUserId();
        String key = normaliseKey(idempotencyKey);

        if (key != null) {
            Optional<DeploymentCreationResult> replay = replay(userId, key, requestHash);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        try {
            DeploymentResponseDTO created = tx.execute(status -> {
                NewDeployment target = plan.apply(userId);
                ProjectMaster project = target.project();
                Deployment rollbackOf = target.rollbackOf();
                checkAdmission(userId);
                // Rollbacks are free; a new deploy is free (free project) or uses a paid credit, else 402.
                Optional<Payment> credit = rollbackOf == null ? billingService.admitDeploy(userId, project)
                        : Optional.empty();
                Instant now = clock.instant();
                ExecutionMode mode = properties.execution().mode();
                Deployment deployment = deploymentRepository.save(Deployment.queue(project, target.commitRef(),
                        rollbackOf == null ? null : rollbackOf.getId(), mode, now));
                // Same transaction as the deployment: if this commits, the job exists. No lost requests.
                jobRepository.save(DeploymentJob.enqueue(deployment.getId(), properties.worker().maxAttempts(), now));
                credit.ifPresent(c -> billingService.useCredit(c, deployment.getId()));
                transitions.recordCreated(deployment, rollbackOf == null ? "Deployment requested"
                        : "Rollback to deployment #" + rollbackOf.getId() + " requested", "user:" + userId);
                if (key != null) {
                    idempotencyRepository.save(new IdempotencyRecord(new IdempotencyRecord.Key(userId, key),
                            requestHash, deployment.getId(), now));
                }
                String modeNote = mode == ExecutionMode.SIMULATED
                        ? "Mode: SIMULATED - no code will be built or run."
                        : "Mode: REAL - it will be built and run in Docker on this machine.";
                if (rollbackOf == null) {
                    logWriter.info(deployment.getId(), "Deployment queued for " + project.getName() + " (branch "
                            + project.getBranch() + "). " + modeNote);
                    auditService.record(AuditEventType.DEPLOYMENT_CREATED, userId, null, null,
                            "deployment #" + deployment.getId() + " of project #" + project.getId());
                } else {
                    logWriter.info(deployment.getId(), "Rollback queued for " + project.getName()
                            + ": redeploying deployment #" + rollbackOf.getId() + " (" + describeVersion(rollbackOf,
                            project) + "). " + modeNote);
                    auditService.record(AuditEventType.DEPLOYMENT_ROLLBACK_REQUESTED, userId, null, null,
                            "deployment #" + deployment.getId() + " rolls back to #" + rollbackOf.getId());
                }
                deploymentRepository.flush();
                return DeploymentMapper.toResponse(deployment);
            });
            return new DeploymentCreationResult(created, false);
        } catch (DataIntegrityViolationException raced) {
            if (key == null) {
                throw raced;
            }
            return awaitWinner(userId, key, requestHash).orElseThrow(() -> raced);
        }
    }

    private static String describeVersion(Deployment source, ProjectMaster project) {
        if (source.getResolvedCommit() != null) {
            return "exact commit " + source.getResolvedCommit();
        }
        if (source.getCommitRef() != null) {
            return "commit " + source.getCommitRef();
        }
        return "it recorded no exact commit (deployed before real builds), so the current head of branch "
                + project.getBranch() + " is deployed";
    }

    /**
     * We lost the race for this key. The winner's transaction may not be committed yet, so look
     * for its result a few times before giving up (Oracle already waits for the winner before
     * reporting the duplicate; this keeps the behaviour the same on every database).
     */
    private Optional<DeploymentCreationResult> awaitWinner(Long userId, String key, String requestHash) {
        for (int i = 0; i < 20; i++) {
            Optional<DeploymentCreationResult> winner = replay(userId, key, requestHash);
            if (winner.isPresent()) {
                return winner;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Optional.empty();
    }

    @Override
    @Transactional
    public DeploymentResponseDTO cancel(Long id) {
        Deployment deployment = findOwned(id);
        Long userId = currentUserService.currentUserId();
        transitions.transition(deployment, DeploymentStatus.CANCELLED, "Cancelled by user", "user:" + userId, null);
        // A job still waiting in the queue is dropped now; a running one stops at its next step.
        jobRepository.cancelIfPending(id, clock.instant());
        logWriter.warn(id, "Deployment cancelled by user");
        auditService.record(AuditEventType.DEPLOYMENT_CANCELLED, userId, null, null, "deployment #" + id);
        Deployment saved = deploymentRepository.saveAndFlush(deployment);
        metrics.deploymentFinished(DeploymentStatus.CANCELLED, saved.getCreatedAt());
        return DeploymentMapper.toResponse(saved);
    }

    /**
     * Its queue job, status history and Idempotency-Key go with it (ON DELETE CASCADE); a rollback that
     * pointed at it keeps working and just loses the link. Its stored image is removed after the commit,
     * unless it is still one of the project's recent images kept for rollback.
     */
    @Override
    @Transactional
    public void delete(Long id) {
        Deployment deployment = findOwned(id);
        if (!deployment.canBeDeleted()) {
            throw new ConflictException(deployment.getStatus() == DeploymentStatus.LIVE
                    ? "Deployment #" + id + " is live. Deploy a newer version first (or delete the project)."
                    : "Deployment #" + id + " is still in progress. Cancel it first.");
        }
        if (jobRepository.isRunning(id)) {
            throw new ConflictException("Deployment #" + id + " is still finishing. Try again in a few seconds.");
        }
        Long projectId = deployment.getProject().getId();
        Long userId = currentUserService.currentUserId();
        String container = deployment.getContainerName();
        boolean hadImage = deployment.getImageRef() != null;
        deploymentLogRepository.deleteByDeploymentId(id);
        deploymentRepository.delete(deployment);
        deploymentRepository.flush();
        auditService.record(AuditEventType.DEPLOYMENT_DELETED, userId, null, null,
                "deployment #" + id + " of project #" + projectId);
        // Read now (the deleted row is already gone in this transaction); used once the delete has committed.
        Set<String> keepImages = Set.copyOf(deploymentRepository.findRecentImageRefs(projectId,
                EnumSet.of(DeploymentStatus.LIVE, DeploymentStatus.SUPERSEDED),
                Limit.of(Math.max(1, properties.execution().keepImagesPerProject()))));
        DeploymentExecutor executor = executors.forMode(deployment.getExecutionMode());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.stop(container); // already stopped when it was replaced; harmless if so
                if (hadImage && !keepImages.isEmpty()) {
                    executor.pruneImages(projectId, keepImages);
                }
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public LogPageResponseDTO logs(Long id, long afterCursor, int limit) {
        Deployment deployment = findOwned(id);
        // Fetch one extra row to learn whether another page exists without a COUNT query.
        List<DeploymentLog> rows = deploymentLogRepository
                .findByDeploymentIdAndIdGreaterThanOrderByIdAsc(id, afterCursor, Limit.of(limit + 1));
        boolean hasMore = rows.size() > limit;
        List<DeploymentLogDTO> items = rows.stream().limit(limit).map(DeploymentMapper::toLog).toList();
        Long nextCursor = items.isEmpty() ? afterCursor : items.getLast().id();
        return new LogPageResponseDTO(items, nextCursor, hasMore, deployment.getStatus());
    }

    @Override
    @Transactional(readOnly = true)
    public DeploymentHistoryResponseDTO history(Long id) {
        findOwned(id);
        DeploymentHistoryResponseDTO.Job job = jobRepository.findByDeploymentId(id)
                .map(j -> new DeploymentHistoryResponseDTO.Job(j.getStatus(), j.getAttempt(), j.getMaxAttempts(),
                        j.getStatus() == JobStatus.PENDING && j.getAttempt() > 0 ? j.getAvailableAt() : null,
                        j.getLastError(), j.getStatus() == JobStatus.RUNNING ? j.getLockedBy() : null))
                .orElse(null);
        List<DeploymentHistoryResponseDTO.Transition> changes = historyRepository.findByDeploymentIdOrderByIdAsc(id)
                .stream()
                .map(c -> new DeploymentHistoryResponseDTO.Transition(c.getFromStatus(), c.getToStatus(), c.getReason(),
                        c.getActor(), c.getAttempt(), c.getOccurredAt()))
                .toList();
        return new DeploymentHistoryResponseDTO(job, changes);
    }

    /** Admission control: a user can't flood the queue (CPU, DB connections, storage) with deployments. */
    private void checkAdmission(Long userId) {
        int limit = properties.deployments().maxActivePerUser();
        if (deploymentRepository.countActiveForOwner(userId, ACTIVE) >= limit) {
            throw new TooManyRequestsException("You already have " + limit
                    + " deployments queued or running. Wait for one to finish or cancel one.", Duration.ofSeconds(30));
        }
    }

    private Optional<DeploymentCreationResult> replay(Long userId, String key, String requestHash) {
        return tx.execute(status -> idempotencyRepository.findById(new IdempotencyRecord.Key(userId, key))
                .map(existing -> {
                    if (!existing.getRequestHash().equals(requestHash)) {
                        throw new IdempotencyKeyReuseException();
                    }
                    Deployment deployment = deploymentRepository.findOwned(existing.getDeploymentId(), userId)
                            .orElseThrow(() -> new ResourceNotFoundException("Deployment", existing.getDeploymentId()));
                    return new DeploymentCreationResult(DeploymentMapper.toResponse(deployment), true);
                }));
    }

    private static String normaliseKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        if (!IDEMPOTENCY_KEY.matcher(trimmed).matches()) {
            throw new FieldValidationException("Idempotency-Key",
                    "must be 1-100 characters: letters, digits, '.', '_', ':' or '-'");
        }
        return trimmed;
    }

    /** Deploys hash "projectId|commitRef" (unchanged since M4, so stored keys keep matching); rollbacks "rollback|id". */
    private static String requestHash(String request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(request.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Deployment findOwned(Long id) {
        return deploymentRepository.findOwned(id, currentUserService.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Deployment", id));
    }
}
