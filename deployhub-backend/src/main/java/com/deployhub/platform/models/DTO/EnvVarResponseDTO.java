package com.deployhub.platform.models.DTO;

import java.time.Instant;

/** An environment variable as the API shows it: the name only. Values are never returned. */
public record EnvVarResponseDTO(String name, Instant createdAt, Instant updatedAt) {
}
