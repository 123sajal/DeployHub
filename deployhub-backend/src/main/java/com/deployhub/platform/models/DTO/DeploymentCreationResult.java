package com.deployhub.platform.models.DTO;

/** {@code replayed} = an earlier request with the same Idempotency-Key already created this deployment. */
public record DeploymentCreationResult(DeploymentResponseDTO deployment, boolean replayed) {
}
