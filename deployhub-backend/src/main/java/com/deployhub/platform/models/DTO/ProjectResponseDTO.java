package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.ApplicationType;

import java.time.Instant;

public record ProjectResponseDTO(
        Long id,
        String name,
        String repositoryUrl,
        String branch,
        ApplicationType appType,
        /** Folder inside the repository that holds the app; null = repository root. */
        String rootDirectory,
        /** True when an access token for a private repository is saved (the token itself is never returned). */
        boolean privateRepository,
        java.time.Instant repositoryTokenUpdatedAt,
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
        /** True for the user's free project: its deploys cost nothing. Otherwise each deploy uses a paid credit. */
        boolean free,
        Instant createdAt,
        Instant updatedAt) {
}
