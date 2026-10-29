package com.deployhub.platform.execution;

import com.deployhub.platform.models.enums.ApplicationType;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;

import java.util.Map;

/**
 * An immutable copy of everything a step needs, read in a short transaction. Steps run for minutes
 * outside any transaction, so they must not hold JPA entities.
 *
 * @param ref            what to fetch: the commit ref if one was requested, otherwise the branch
 * @param resolvedCommit set once the BUILDING step has fetched the source
 * @param imageRef       set once the BUILDING step has built the image
 * @param environment    decrypted environment variables for the app (runtime only); never log this record
 * @param rootDirectory  folder inside the repository that holds the app; null = the repository root
 * @param repositoryToken decrypted access token for a private repository (null = public); never log this record
 */
public record DeploymentSpec(Long deploymentId, DeploymentStatus status, ExecutionMode mode, int attempt,
                             Long projectId, String projectName, String repositoryUrl, String ref,
                             ApplicationType appType, String buildCommand, String startCommand, int appPort,
                             String healthCheckPath, String resolvedCommit, String imageRef,
                             Map<String, String> environment, String rootDirectory, String repositoryToken) {

    public DeploymentSpec(Long deploymentId, DeploymentStatus status, ExecutionMode mode, int attempt, Long projectId,
                          String projectName, String repositoryUrl, String ref, ApplicationType appType,
                          String buildCommand, String startCommand, int appPort, String healthCheckPath,
                          String resolvedCommit, String imageRef, Map<String, String> environment, String rootDirectory) {
        this(deploymentId, status, mode, attempt, projectId, projectName, repositoryUrl, ref, appType, buildCommand,
                startCommand, appPort, healthCheckPath, resolvedCommit, imageRef, environment, rootDirectory, null);
    }

    public DeploymentSpec(Long deploymentId, DeploymentStatus status, ExecutionMode mode, int attempt, Long projectId,
                          String projectName, String repositoryUrl, String ref, ApplicationType appType,
                          String buildCommand, String startCommand, int appPort, String healthCheckPath,
                          String resolvedCommit, String imageRef, Map<String, String> environment) {
        this(deploymentId, status, mode, attempt, projectId, projectName, repositoryUrl, ref, appType, buildCommand,
                startCommand, appPort, healthCheckPath, resolvedCommit, imageRef, environment, null, null);
    }

    public DeploymentSpec(Long deploymentId, DeploymentStatus status, ExecutionMode mode, int attempt, Long projectId,
                          String projectName, String repositoryUrl, String ref, ApplicationType appType,
                          String buildCommand, String startCommand, int appPort, String healthCheckPath,
                          String resolvedCommit, String imageRef) {
        this(deploymentId, status, mode, attempt, projectId, projectName, repositoryUrl, ref, appType, buildCommand,
                startCommand, appPort, healthCheckPath, resolvedCommit, imageRef, Map.of());
    }

    @Override
    public String toString() {
        return "DeploymentSpec[deployment=" + deploymentId + ", status=" + status + ", environment="
                + environment.keySet() + " (values hidden), repositoryToken=" + (repositoryToken == null ? "none" : "***") + "]";
    }
}
