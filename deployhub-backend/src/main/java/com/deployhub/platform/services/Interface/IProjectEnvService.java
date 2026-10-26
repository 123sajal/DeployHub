package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.EnvVarResponseDTO;

import java.util.List;

/** Environment variables of a project. Values are write-only: stored encrypted, never returned. */
public interface IProjectEnvService {

    List<EnvVarResponseDTO> list(Long projectId);

    /** Creates or replaces the variable. Takes effect at the project's next deployment. */
    EnvVarResponseDTO set(Long projectId, String name, String value);

    void delete(Long projectId, String name);
}
