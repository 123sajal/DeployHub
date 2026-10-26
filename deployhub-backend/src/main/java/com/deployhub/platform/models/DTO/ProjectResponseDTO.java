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
        Integer appPort,
        /** The port actually used: appPort, or the app type's default. */
        int effectivePort,
        String healthCheckPath,
        /** The project's fixed address on this machine, once it has run for real; null before. */
        String localUrl,
        /** True when the project has a public internet link (opt-in). */
        boolean publicLinkEnabled,
        /** The public link as last seen, while enabled. */
        String publicLink,
        Instant createdAt,
        Instant updatedAt) {
}
