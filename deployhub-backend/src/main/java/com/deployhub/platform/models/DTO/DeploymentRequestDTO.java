package com.deployhub.platform.models.DTO;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The body is optional; without a commit ref the branch head is deployed. */
public record DeploymentRequestDTO(
        @Size(max = 100)
        @Pattern(regexp = "^[A-Za-z0-9._/-]+$", message = "is not a valid commit or tag reference")
        String commitRef) {
}
