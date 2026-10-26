package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.exceptions.ConflictException;
import com.deployhub.platform.exceptions.ResourceNotFoundException;
import com.deployhub.platform.exceptions.ServiceUnavailableException;
import com.deployhub.platform.execution.DeploymentExecutor;
import com.deployhub.platform.execution.DeploymentExecutors;
import com.deployhub.platform.mappers.ProjectMapper;
import com.deployhub.platform.models.DTO.PublicLinkResponseDTO;
import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.repositories.DeploymentRepository;
import com.deployhub.platform.repositories.ProjectRepository;
import com.deployhub.platform.services.Interface.IAuditService;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IProjectPublicLinkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * Public links: a project's address made reachable from the internet through a tunnel. The tunnel work runs
 * outside database transactions (starting one can take a minute). Quick-tunnel links change when the tunnel
 * restarts (e.g. after Docker restarts), so a periodic check re-reads them and restarts missing tunnels.
 */
@Slf4j
@Service
public class ProjectPublicLinkService implements IProjectPublicLinkService {

    private final ProjectRepository projects;
    private final DeploymentRepository deployments;
    private final DeploymentExecutors executors;
    private final ICurrentUserService currentUserService;
    private final IAuditService auditService;
    private final TransactionTemplate tx;

    public ProjectPublicLinkService(ProjectRepository projects, DeploymentRepository deployments,
                                    DeploymentExecutors executors, ICurrentUserService currentUserService,
                                    IAuditService auditService, PlatformTransactionManager transactionManager) {
        this.projects = projects;
        this.deployments = deployments;
        this.executors = executors;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public PublicLinkResponseDTO get(Long projectId) {
        ProjectMaster project = tx.execute(s -> owned(projectId));
        if (project.isPublicLinkOn()) {
            String current = docker().currentPublicLink(projectId);
            if (current != null && !current.equals(project.getPublicLinkUrl())) {
                saveUrl(projectId, current);
                return new PublicLinkResponseDTO(true, current, ProjectMapper.publicUrl(project));
            }
        }
        return toResponse(project);
    }

    @Override
    public PublicLinkResponseDTO enable(Long projectId) {
        Long userId = currentUserService.currentUserId();
        ProjectMaster project = tx.execute(s -> {
            ProjectMaster p = owned(projectId);
            boolean runsForReal = deployments.findFirstByProjectIdAndStatus(projectId, DeploymentStatus.LIVE)
                    .filter(d -> d.getExecutionMode() == ExecutionMode.REAL && d.getContainerName() != null)
                    .isPresent();
            if (!runsForReal || p.getPublicPort() == null) {
                throw new ConflictException("Deploy " + p.getName() + " first: only a live app can get a public link");
            }
            return p;
        });
        String link;
        try {
            link = docker().startPublicLink(projectId);
        } catch (IllegalStateException e) {
            throw new ServiceUnavailableException(e.getMessage(), e);
        }
        tx.executeWithoutResult(s -> {
            ProjectMaster p = projects.findById(projectId).orElseThrow();
            p.setPublicLinkState("ON");
            p.setPublicLinkUrl(link);
            projects.saveAndFlush(p);
            auditService.record(AuditEventType.PUBLIC_LINK_ON, userId, null, null,
                    "project #" + projectId + ": " + link);
        });
        return new PublicLinkResponseDTO(true, link, ProjectMapper.publicUrl(project));
    }

    @Override
    public PublicLinkResponseDTO disable(Long projectId) {
        Long userId = currentUserService.currentUserId();
        ProjectMaster project = tx.execute(s -> owned(projectId));
        docker().stopPublicLink(projectId);
        tx.executeWithoutResult(s -> {
            ProjectMaster p = projects.findById(projectId).orElseThrow();
            p.setPublicLinkState("OFF");
            p.setPublicLinkUrl(null);
            projects.saveAndFlush(p);
            auditService.record(AuditEventType.PUBLIC_LINK_OFF, userId, null, null, "project #" + projectId);
        });
        return new PublicLinkResponseDTO(false, null, ProjectMapper.publicUrl(project));
    }

    /**
     * Keeps public links true: a restarted tunnel has a new link, and a missing one (e.g. removed by hand, or
     * Docker reset) is started again. Runs in every backend process; starting is idempotent (a running tunnel
     * is adopted), so two processes doing it at once is harmless.
     */
    @Scheduled(fixedDelayString = "${deployhub.gateway.public-link-refresh}",
            initialDelayString = "${deployhub.gateway.public-link-refresh}")
    public void refreshAll() {
        try {
            for (ProjectMaster project : tx.execute(s -> projects.findWithPublicLinkOn())) {
                refresh(project.getId(), project.getPublicLinkUrl());
            }
        } catch (RuntimeException e) {
            log.debug("Could not check public links: {}", e.getMessage());
        }
    }

    void refresh(Long projectId, String knownUrl) {
        try {
            String current = docker().currentPublicLink(projectId);
            if (current == null) {
                current = docker().startPublicLink(projectId);
            }
            if (!Objects.equals(current, knownUrl)) {
                saveUrl(projectId, current);
            }
        } catch (RuntimeException e) {
            log.warn("Public link of project {} could not be checked: {}", projectId, e.getMessage());
        }
    }

    private void saveUrl(Long projectId, String url) {
        tx.executeWithoutResult(s -> projects.findById(projectId).ifPresent(p -> {
            p.setPublicLinkUrl(url);
            projects.saveAndFlush(p);
        }));
    }

    private DeploymentExecutor docker() {
        return executors.forMode(ExecutionMode.REAL);
    }

    private ProjectMaster owned(Long projectId) {
        return projects.findByIdAndOwnerId(projectId, currentUserService.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }

    private static PublicLinkResponseDTO toResponse(ProjectMaster project) {
        return new PublicLinkResponseDTO(project.isPublicLinkOn(),
                project.isPublicLinkOn() ? project.getPublicLinkUrl() : null, ProjectMapper.publicUrl(project));
    }
}
