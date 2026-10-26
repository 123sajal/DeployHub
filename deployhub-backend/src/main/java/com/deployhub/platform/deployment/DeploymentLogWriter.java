package com.deployhub.platform.deployment;

import com.deployhub.platform.models.entities.DeploymentLog;
import com.deployhub.platform.models.enums.LogLevel;
import com.deployhub.platform.repositories.DeploymentLogRepository;
import com.deployhub.platform.utils.LogSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** The only way log events are written, so every line goes through the secret sanitizer. */
@Component
@RequiredArgsConstructor
public class DeploymentLogWriter {

    private final DeploymentLogRepository repository;
    private final Clock clock;

    public void info(Long deploymentId, String message) {
        write(deploymentId, LogLevel.INFO, message);
    }

    public void warn(Long deploymentId, String message) {
        write(deploymentId, LogLevel.WARN, message);
    }

    public void error(Long deploymentId, String message) {
        write(deploymentId, LogLevel.ERROR, message);
    }

    /**
     * Blank lines (build tools print plenty) are skipped: they carry nothing, and Oracle stores an empty
     * string as NULL, which the NOT NULL message column rejects.
     */
    private void write(Long deploymentId, LogLevel level, String message) {
        String safe = LogSanitizer.sanitize(message);
        if (safe.isBlank()) {
            return;
        }
        repository.save(new DeploymentLog(deploymentId, level, safe, clock.instant()));
    }
}
