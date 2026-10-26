package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.LogLevel;

import java.time.Instant;

public record DeploymentLogDTO(Long id, LogLevel level, String message, Instant occurredAt) {
}
