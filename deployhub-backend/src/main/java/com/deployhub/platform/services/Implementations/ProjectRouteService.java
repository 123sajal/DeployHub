package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.deployment.ProjectGateways;
import com.deployhub.platform.exceptions.FieldValidationException;
import com.deployhub.platform.exceptions.ResourceNotFoundException;
import com.deployhub.platform.models.DTO.ProjectRoutesRequestDTO;
import com.deployhub.platform.models.DTO.RouteResponseDTO;
import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.entities.ProjectRoute;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.repositories.ProjectRepository;
import com.deployhub.platform.repositories.ProjectRouteRepository;
import com.deployhub.platform.services.Interface.IAuditService;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.IProjectRouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectRouteService implements IProjectRouteService {

    private final ProjectRepository projects;
    private final ProjectRouteRepository routes;
    private final ProjectGateways gateways;
    private final ICurrentUserService currentUserService;
    private final IAuditService auditService;

    @Override
    @Transactional(readOnly = true)
    public List<RouteResponseDTO> list(Long projectId) {
        requireOwned(projectId);
        return toResponse(routes.findByProjectIdOrderByPathPrefixAsc(projectId));
    }

    @Override
    @Transactional
    public List<RouteResponseDTO> replace(Long projectId, ProjectRoutesRequestDTO request) {
        Long userId = currentUserService.currentUserId();
        requireOwned(projectId);
        Set<String> seen = new HashSet<>();
        for (ProjectRoutesRequestDTO.Route route : request.routes()) {
            String prefix = normalise(route.pathPrefix());
            if (!seen.add(prefix)) {
                throw new FieldValidationException("routes", "the prefix " + prefix + " appears twice");
            }
            if (route.targetProjectId().equals(projectId)) {
                throw new FieldValidationException("routes", "a project can't route to itself");
            }
            // Only the user's own projects: another user's id is "not found", like everywhere else.
            projects.findByIdAndOwnerId(route.targetProjectId(), userId).orElseThrow(() ->
                    new FieldValidationException("routes", "project #" + route.targetProjectId() + " was not found"));
        }
        routes.deleteByProjectId(projectId);
        List<ProjectRoute> saved = routes.saveAllAndFlush(request.routes().stream()
                .map(r -> new ProjectRoute(projectId, normalise(r.pathPrefix()), r.targetProjectId()))
                .toList());
        auditService.record(AuditEventType.ROUTES_CHANGED, userId, null, null, "project #" + projectId + ": "
                + saved.stream().map(r -> r.getPathPrefix() + " -> #" + r.getTargetProjectId())
                .collect(Collectors.joining(", ")));
        // The address changes right away (once committed), not only at the next deployment.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    gateways.refresh(projectId);
                } catch (RuntimeException e) {
                    log.warn("Routes of project {} saved, but its address could not be updated yet", projectId, e);
                }
            }
        });
        return toResponse(saved);
    }

    /** "/api/" and "/api" are the same route; stored without the trailing slash. */
    private static String normalise(String prefix) {
        String trimmed = prefix.trim();
        return trimmed.length() > 1 && trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private void requireOwned(Long projectId) {
        projects.findByIdAndOwnerId(projectId, currentUserService.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }

    private List<RouteResponseDTO> toResponse(List<ProjectRoute> list) {
        return list.stream().map(r -> new RouteResponseDTO(r.getPathPrefix(), r.getTargetProjectId(),
                projects.findById(r.getTargetProjectId()).map(ProjectMaster::getName).orElse("?"))).toList();
    }
}
