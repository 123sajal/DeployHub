package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.DeploymentCreationResult;
import com.deployhub.platform.models.DTO.DeploymentHistoryResponseDTO;
import com.deployhub.platform.models.DTO.DeploymentRequestDTO;
import com.deployhub.platform.models.DTO.DeploymentResponseDTO;
import com.deployhub.platform.models.DTO.LogPageResponseDTO;
import com.deployhub.platform.models.DTO.PageResponseDTO;
import com.deployhub.platform.models.enums.DeploymentStatus;

public interface IDeploymentService {

    PageResponseDTO<DeploymentResponseDTO> list(Long projectId, DeploymentStatus status, int page, int size);

    DeploymentResponseDTO get(Long id);

    /**
     * Queues a deployment. With an {@code idempotencyKey}, repeating the same request returns the
     * deployment created the first time instead of creating another one.
     */
    DeploymentCreationResult create(Long projectId, DeploymentRequestDTO request, String idempotencyKey);

    /**
     * Queues a new deployment that redeploys an earlier one (it must have been LIVE and since replaced).
     * Takes an {@code idempotencyKey} just like {@link #create}.
     */
    DeploymentCreationResult rollback(Long deploymentId, String idempotencyKey);

    DeploymentResponseDTO cancel(Long id);

    LogPageResponseDTO logs(Long id, long afterCursor, int limit);

    DeploymentHistoryResponseDTO history(Long id);
}
