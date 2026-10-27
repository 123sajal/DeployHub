package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.EnvRequirementsResponseDTO;

import java.util.List;

public interface IEnvRequirementsService {

    /** The variables the project's app needs, from its repository, with what is already set. */
    EnvRequirementsResponseDTO requirements(Long projectId);

    /** Fills in the given missing variables that DeployHub can fill (secrets, example values, database aliases). */
    EnvRequirementsResponseDTO fillIn(Long projectId, List<String> names);
}
