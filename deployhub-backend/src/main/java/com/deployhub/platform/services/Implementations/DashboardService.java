package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.mappers.DeploymentMapper;
import com.deployhub.platform.models.DTO.DashboardSummaryResponseDTO;
import com.deployhub.platform.models.DTO.DeploymentMetricsResponseDTO;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.JobStatus;
import com.deployhub.platform.repositories.DeploymentJobRepository;
import com.deployhub.platform.repositories.DeploymentRepository;
import com.deployhub.platform.repositories.ProjectRepository;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LongSummaryStatistics;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService implements IDashboardService {

    private static final int RECENT_LIMIT = 8;
    private static final Duration METRICS_WINDOW = Duration.ofHours(1);
    /** Enough samples for a stable average; keeps the query bounded for very busy users. */
    private static final int MAX_WAIT_SAMPLES = 1000;

    private final ProjectRepository projectRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentJobRepository jobRepository;
    private final ICurrentUserService currentUserService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public DashboardSummaryResponseDTO summary() {
        Long ownerId = currentUserService.currentUserId();
        Map<DeploymentStatus, Long> counts = new EnumMap<>(DeploymentStatus.class);
        deploymentRepository.countByStatus(ownerId).forEach(c -> counts.put(c.getStatus(), c.getTotal()));

        var recent = deploymentRepository.search(ownerId, null, null,
                        PageRequest.of(0, RECENT_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                .map(DeploymentMapper::toResponse)
                .getContent();

        return new DashboardSummaryResponseDTO(
                projectRepository.countByOwnerId(ownerId),
                count(counts, DeploymentStatus.QUEUED),
                count(counts, DeploymentStatus.BUILDING) + count(counts, DeploymentStatus.STARTING),
                count(counts, DeploymentStatus.LIVE) + count(counts, DeploymentStatus.SUPERSEDED),
                count(counts, DeploymentStatus.FAILED),
                count(counts, DeploymentStatus.CANCELLED),
                recent);
    }

    /**
     * Your own deployments only (tenant isolation), except the queue depth: that is platform-wide, because
     * it's what explains why your deployment is waiting, and it reveals no other user's data.
     */
    @Override
    @Transactional(readOnly = true)
    public DeploymentMetricsResponseDTO metrics() {
        Long ownerId = currentUserService.currentUserId();
        Instant now = clock.instant();
        Instant since = now.minus(METRICS_WINDOW);

        var queue = new DeploymentMetricsResponseDTO.Queue(
                jobRepository.countByStatusAndAvailableAtLessThanEqual(JobStatus.PENDING, now),
                jobRepository.countByStatusAndAvailableAtGreaterThan(JobStatus.PENDING, now),
                jobRepository.countByStatus(JobStatus.RUNNING));

        // Queue wait = request until a worker first picked the deployment up (QUEUED -> BUILDING).
        LongSummaryStatistics waits = deploymentRepository.findQueueWaits(ownerId, since, Limit.of(MAX_WAIT_SAMPLES))
                .stream()
                .mapToLong(w -> Math.max(0, Duration.between(w.getCreatedAt(), w.getStartedAt()).toMillis()))
                .summaryStatistics();

        Map<DeploymentStatus, Long> finished = new EnumMap<>(DeploymentStatus.class);
        deploymentRepository.countFinishedSince(ownerId, since).forEach(c -> finished.put(c.getStatus(), c.getTotal()));
        long succeeded = count(finished, DeploymentStatus.LIVE) + count(finished, DeploymentStatus.SUPERSEDED);
        long failed = count(finished, DeploymentStatus.FAILED);
        long cancelled = count(finished, DeploymentStatus.CANCELLED);

        return new DeploymentMetricsResponseDTO((int) METRICS_WINDOW.toMinutes(), queue,
                waits.getCount() == 0 ? null : Math.round(waits.getAverage()),
                waits.getCount() == 0 ? null : waits.getMax(),
                succeeded + failed + cancelled, succeeded, failed, cancelled,
                succeeded + failed == 0 ? null : (double) succeeded / (succeeded + failed));
    }

    private static long count(Map<DeploymentStatus, Long> counts, DeploymentStatus status) {
        return counts.getOrDefault(status, 0L);
    }
}
