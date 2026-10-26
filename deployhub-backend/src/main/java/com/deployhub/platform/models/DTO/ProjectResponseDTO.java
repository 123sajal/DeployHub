package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.ApplicationType;

import java.time.Instant;

public record ProjectResponseDTO(
        Long id,
        String name,
        String repositoryUrl,
        String branch,
        ApplicationType appType,
        String buildCommand,
        String startCommand,
        Instant createdAt,
        Instant updatedAt) {
}
