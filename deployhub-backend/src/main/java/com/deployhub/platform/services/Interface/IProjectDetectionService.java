package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.DetectRequestDTO;
import com.deployhub.platform.models.DTO.DetectResponseDTO;

public interface IProjectDetectionService {

    /** Suggests the application type and commands from the repository's files (read-only). */
    DetectResponseDTO detect(DetectRequestDTO request);
}
