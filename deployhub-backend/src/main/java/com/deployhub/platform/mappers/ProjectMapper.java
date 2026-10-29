package com.deployhub.platform.mappers;

import com.deployhub.platform.models.DTO.ProjectRequestDTO;
import com.deployhub.platform.models.DTO.ProjectResponseDTO;
import com.deployhub.platform.models.entities.ProjectMaster;

public final class ProjectMapper {

    private ProjectMapper() {
    }

    public static ProjectResponseDTO toResponse(ProjectMaster project) {
        return new ProjectResponseDTO(project.getId(), project.getName(), project.getRepositoryUrl(),
                project.getBranch(), project.getAppType(), project.getRootDirectory(), project.getRepoTokenEncrypted() != null,
                project.getRepoTokenUpdatedAt(), project.getBuildCommand(), project.getStartCommand(),
                project.getAppPort(), effectivePort(project), project.getHealthCheckPath(), publicUrl(project),
                project.isPublicLinkOn(), project.isPublicLinkOn() ? project.getPublicLinkUrl() : null,
                project.isFree(), project.getCreatedAt(), project.getUpdatedAt());
    }

    /** Address proxies are published on 127.0.0.1 only (M7 puts a public proxy in front). */
    public static String publicUrl(ProjectMaster project) {
        return project.getPublicPort() == null ? null : "http://localhost:" + project.getPublicPort();
    }

    public static int effectivePort(ProjectMaster project) {
        return project.getAppPort() != null ? project.getAppPort() : project.getAppType().defaultPort();
    }

    public static void apply(ProjectRequestDTO request, ProjectMaster project) {
        project.setName(request.name());
        project.setRepositoryUrl(request.repositoryUrl().trim());
        project.setBranch(request.branch());
        project.setAppType(request.appType());
        project.setRootDirectory(normaliseFolder(request.rootDirectory()));
        project.setBuildCommand(blankToNull(request.buildCommand()));
        project.setStartCommand(blankToNull(request.startCommand()));
        project.setAppPort(request.appPort());
        project.setHealthCheckPath(blankToNull(request.healthCheckPath()));
    }

    /** "./backend/" -> "backend"; empty -> null (the repository root). The DTO pattern already rejected "..". */
    static String normaliseFolder(String value) {
        if (value == null) {
            return null;
        }
        String folder = value.trim();
        if (folder.startsWith("./")) {
            folder = folder.substring(2);
        }
        if (folder.endsWith("/")) {
            folder = folder.substring(0, folder.length() - 1);
        }
        return folder.isEmpty() ? null : folder;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
