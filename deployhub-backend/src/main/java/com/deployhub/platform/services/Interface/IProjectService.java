package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.PageResponseDTO;
import com.deployhub.platform.models.DTO.ProjectRequestDTO;
import com.deployhub.platform.models.DTO.ProjectResponseDTO;
import com.deployhub.platform.models.enums.ApplicationType;

public interface IProjectService {

    PageResponseDTO<ProjectResponseDTO> list(String search, ApplicationType appType, int page, int size);

    ProjectResponseDTO get(Long id);

    ProjectResponseDTO create(ProjectRequestDTO request);

    ProjectResponseDTO update(Long id, ProjectRequestDTO request);

    void delete(Long id);

    /** Forgets the project's access token: its repository is read as public again. */
    ProjectResponseDTO removeRepositoryToken(Long id);
}
