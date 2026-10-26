package com.deployhub.platform.mappers;

import com.deployhub.platform.models.DTO.ProjectRequestDTO;
import com.deployhub.platform.models.DTO.ProjectResponseDTO;
import com.deployhub.platform.models.entities.ProjectMaster;

public final class ProjectMapper {

    private ProjectMapper() {
    }

    public static ProjectResponseDTO toResponse(ProjectMaster project) {
        return new ProjectResponseDTO(project.getId(), project.getName(), project.getRepositoryUrl(),
                project.getBranch(), project.getAppType(), project.getBuildCommand(), project.getStartCommand(),
                project.getCreatedAt(), project.getUpdatedAt());
    }

    public static void apply(ProjectRequestDTO request, ProjectMaster project) {
        project.setName(request.name());
        project.setRepositoryUrl(request.repositoryUrl().trim());
        project.setBranch(request.branch());
        project.setAppType(request.appType());
        project.setBuildCommand(blankToNull(request.buildCommand()));
        project.setStartCommand(request.startCommand().trim());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
