package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.deployment.ProjectGateways;
import com.deployhub.platform.exceptions.ConflictException;
import com.deployhub.platform.execution.DeploymentExecutor;
import com.deployhub.platform.exceptions.ResourceNotFoundException;
import com.deployhub.platform.mappers.ProjectMapper;
import com.deployhub.platform.models.DTO.PageResponseDTO;
import com.deployhub.platform.models.DTO.ProjectRequestDTO;
import com.deployhub.platform.models.DTO.ProjectResponseDTO;
import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.entities.ProjectDatabase;
import com.deployhub.platform.models.entities.ProjectRoute;
import com.deployhub.platform.repositories.ProjectDatabaseRepository;
import com.deployhub.platform.services.Interface.IProjectDatabaseService;
import com.deployhub.platform.repositories.ProjectRouteRepository;
import com.deployhub.platform.models.enums.ApplicationType;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.repositories.DeploymentLogRepository;
import com.deployhub.platform.repositories.DeploymentRepository;
import com.deployhub.platform.repositories.ProjectRepository;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.services.Interface.IAuditService;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService implements IProjectService {

    private final ProjectRepository projectRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentLogRepository deploymentLogRepository;
    private final ICurrentUserService currentUserService;
    private final IAuditService auditService;
    private final List<DeploymentExecutor> executors;
    private final ProjectRouteRepository routeRepository;
    private final ProjectGateways gateways;
    private final ProjectDatabaseRepository projectDatabaseRepository;
    private final IProjectDatabaseService databaseService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponseDTO<ProjectResponseDTO> list(String search, ApplicationType appType, int page, int size) {
        String pattern = "%" + (search == null ? "" : escapeLike(search.trim().toLowerCase(Locale.ROOT))) + "%";
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return PageResponseDTO.from(
                projectRepository.search(currentUserService.currentUserId(), pattern, appType, pageable),
                ProjectMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectResponseDTO get(Long id) {
        return ProjectMapper.toResponse(findOwned(id));
    }

    @Override
    @Transactional
    public ProjectResponseDTO create(ProjectRequestDTO request) {
        Long ownerId = currentUserService.currentUserId();
        if (projectRepository.existsByOwnerIdAndName(ownerId, request.name())) {
            throw new ConflictException("A project named '" + request.name() + "' already exists");
        }
        ProjectMaster project = new ProjectMaster();
        ProjectMapper.apply(request, project);
        project.setOwner(currentUserService.currentUserReference());
        Instant now = clock.instant();
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        // The unique (owner_id, name) constraint is the real guard against a race between two creates.
        projectRepository.saveAndFlush(project);
        auditService.record(AuditEventType.PROJECT_CREATED, ownerId, null, null, "project #" + project.getId());
        return ProjectMapper.toResponse(project);
    }

    @Override
    @Transactional
    public ProjectResponseDTO update(Long id, ProjectRequestDTO request) {
        ProjectMaster project = findOwned(id);
        if (projectRepository.existsByOwnerIdAndNameAndIdNot(project.getOwner().getId(), request.name(), id)) {
            throw new ConflictException("A project named '" + request.name() + "' already exists");
        }
        ProjectMapper.apply(request, project);
        project.setUpdatedAt(clock.instant());
        projectRepository.saveAndFlush(project);
        auditService.record(AuditEventType.PROJECT_UPDATED, project.getOwner().getId(), null, null, "project #" + id);
        return ProjectMapper.toResponse(project);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ProjectMaster project = findOwned(id);
        if (deploymentRepository.existsByProjectIdAndStatusIn(id,
                EnumSet.of(DeploymentStatus.QUEUED, DeploymentStatus.BUILDING, DeploymentStatus.STARTING))) {
            throw new ConflictException("Cancel the project's in-progress deployments before deleting it");
        }
        List<String> running = deploymentRepository.findLiveContainerNames(id);
        Optional<ProjectDatabase> database = projectDatabaseRepository.findByProjectId(id);
        List<Long> routedHere = routeRepository.findByTargetProjectId(id).stream()
                .map(ProjectRoute::getProjectId).filter(other -> !other.equals(id)).distinct().toList();
        deploymentLogRepository.deleteByProjectId(id);
        deploymentRepository.deleteByProjectId(id);
        projectRepository.delete(project);
        // Stop the live app and remove its images only once the delete has committed.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (DeploymentExecutor executor : executors) {
                    executor.stopPublicLink(id);
                    running.forEach(executor::stop);
                    executor.pruneImages(id, Set.of());
                }
                // The project's database goes with it (its row was deleted by the cascade).
                database.ifPresent(db -> databaseService.dropAfterProjectDeleted(db.getDatabaseName(), db.getUsername()));
                try {
                    gateways.removed(id, routedHere); // their routes to it were deleted with it
                } catch (RuntimeException e) {
                    log.warn("Could not remove the address of deleted project {}", id, e);
                }
            }
        });
        auditService.record(AuditEventType.PROJECT_DELETED, project.getOwner().getId(), null, null,
                "project #" + id + " (" + project.getName() + ")");
    }

    private ProjectMaster findOwned(Long id) {
        return projectRepository.findByIdAndOwnerId(id, currentUserService.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", id));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
