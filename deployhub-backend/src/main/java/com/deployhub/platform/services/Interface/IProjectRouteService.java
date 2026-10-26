package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.ProjectRoutesRequestDTO;
import com.deployhub.platform.models.DTO.RouteResponseDTO;

import java.util.List;

/** Which path prefixes of a project's address go to other projects (e.g. /api to the backend). */
public interface IProjectRouteService {

    List<RouteResponseDTO> list(Long projectId);

    /** Replaces all routes and updates the project's address right away. */
    List<RouteResponseDTO> replace(Long projectId, ProjectRoutesRequestDTO request);
}
