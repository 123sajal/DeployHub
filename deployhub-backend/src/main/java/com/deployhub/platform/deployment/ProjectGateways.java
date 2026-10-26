package com.deployhub.platform.deployment;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.execution.DeploymentExecutor;
import com.deployhub.platform.execution.DeploymentExecutors;
import com.deployhub.platform.mappers.ProjectMapper;
import com.deployhub.platform.models.entities.Deployment;
import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.entities.ProjectRoute;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.repositories.DeploymentRepository;
import com.deployhub.platform.repositories.ProjectRepository;
import com.deployhub.platform.repositories.ProjectRouteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps each project's fixed address (http://localhost:publicPort) pointing at the right apps. Called after
 * a project goes live, when its routes change, and when a project is deleted. Reads the current state from
 * the database (live deployments, routes) and hands it to the REAL executor, which rewrites the proxy.
 * Only projects whose apps really run get an address; simulated deployments run nothing.
 */
@Slf4j
@Component
public class ProjectGateways {

    private final ProjectRepository projects;
    private final DeploymentRepository deployments;
    private final ProjectRouteRepository routes;
    private final DeploymentExecutors executors;
    private final DeployHubProperties.Gateway settings;
    private final TransactionTemplate tx;

    public ProjectGateways(ProjectRepository projects, DeploymentRepository deployments, ProjectRouteRepository routes,
                           DeploymentExecutors executors, DeployHubProperties properties,
                           PlatformTransactionManager transactionManager) {
        this.projects = projects;
        this.deployments = deployments;
        this.routes = routes;
        this.executors = executors;
        this.settings = properties.gateway();
        // Its own transaction: callers may be inside one that already committed (after-commit hooks).
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Refreshes the project's own address and those of every project that routes to it. */
    public void refreshAffectedBy(Long projectId) {
        Set<Long> affected = new LinkedHashSet<>();
        affected.add(projectId);
        affected.addAll(tx.execute(s -> routes.findByTargetProjectId(projectId).stream()
                .map(ProjectRoute::getProjectId).toList()));
        affected.forEach(this::refresh);
    }

    /** Points the project's address at its current live app and route targets (creating it on first use). */
    public void refresh(Long projectId) {
        DeploymentExecutor.Gateway gateway;
        try {
            gateway = tx.execute(s -> plan(projectId));
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException raced) {
            // Another process took the same port, or edited the project, at the same moment: plan again.
            gateway = tx.execute(s -> plan(projectId));
        }
        if (gateway != null) {
            executors.forMode(ExecutionMode.REAL).publish(gateway);
        }
    }

    /** After a project is deleted: remove its address and refresh the projects that routed to it. */
    public void removed(Long projectId, List<Long> projectsThatRoutedToIt) {
        executors.forMode(ExecutionMode.REAL).unpublish(projectId);
        projectsThatRoutedToIt.forEach(this::refresh);
    }

    private DeploymentExecutor.Gateway plan(Long projectId) {
        ProjectMaster project = projects.findById(projectId).orElse(null);
        if (project == null) {
            return null;
        }
        Optional<Deployment> live = realLive(projectId);
        List<ProjectRoute> projectRoutes = routes.findByProjectIdOrderByPathPrefixAsc(projectId);
        if (project.getPublicPort() == null) {
            if (live.isEmpty()) {
                return null; // nothing runs for real yet: no address needed
            }
            project.setPublicPort(freePort());
            projects.saveAndFlush(project);
        }
        List<DeploymentExecutor.Route> routeTargets = new ArrayList<>();
        for (ProjectRoute route : projectRoutes) {
            ProjectMaster target = projects.findById(route.getTargetProjectId()).orElse(null);
            Optional<Deployment> targetLive = realLive(route.getTargetProjectId());
            routeTargets.add(new DeploymentExecutor.Route(route.getPathPrefix(),
                    targetLive.map(Deployment::getContainerName).orElse(null),
                    targetLive.map(d -> containerPort(d, target)).orElse(null),
                    target == null ? "?" : target.getName()));
        }
        return new DeploymentExecutor.Gateway(projectId, project.getName(), project.getPublicPort(),
                live.map(Deployment::getContainerName).orElse(null),
                live.map(d -> containerPort(d, project)).orElse(null), routeTargets);
    }

    private Optional<Deployment> realLive(Long projectId) {
        return deployments.findFirstByProjectIdAndStatus(projectId, DeploymentStatus.LIVE)
                .filter(d -> d.getExecutionMode() == ExecutionMode.REAL && d.getContainerName() != null);
    }

    /** The port recorded at go-live; deployments from before it was recorded use the project's setting. */
    private static Integer containerPort(Deployment deployment, ProjectMaster project) {
        if (deployment.getContainerPort() != null) {
            return deployment.getContainerPort();
        }
        return project == null ? null : ProjectMapper.effectivePort(project);
    }

    /** Lowest free port in the range. The unique index on public_port settles a race between two processes. */
    private int freePort() {
        Set<Integer> used = new HashSet<>(projects.findUsedPublicPorts());
        for (int port = settings.portRangeStart(); port <= settings.portRangeEnd(); port++) {
            if (!used.contains(port)) {
                return port;
            }
        }
        throw new IllegalStateException("No free project address between " + settings.portRangeStart() + " and "
                + settings.portRangeEnd());
    }
}
