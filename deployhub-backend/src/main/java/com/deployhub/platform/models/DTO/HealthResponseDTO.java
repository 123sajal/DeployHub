package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.ExecutionMode;

import java.time.Instant;
import java.util.List;

public record HealthResponseDTO(
        String status,
        String database,
        String databaseProduct,
        List<String> activeProfiles,
        ExecutionMode deploymentExecutionMode,
        Instant timestamp) {
}
