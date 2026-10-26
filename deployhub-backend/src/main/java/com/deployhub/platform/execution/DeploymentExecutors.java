package com.deployhub.platform.execution;

import com.deployhub.platform.models.enums.ExecutionMode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Picks the executor for a deployment's execution mode (each deployment keeps the mode it was created with). */
@Component
public class DeploymentExecutors {

    private final Map<ExecutionMode, DeploymentExecutor> byMode = new EnumMap<>(ExecutionMode.class);

    public DeploymentExecutors(List<DeploymentExecutor> executors) {
        executors.stream()
                .sorted(Comparator.comparingInt(DeploymentExecutor::priority))
                .forEach(executor -> byMode.put(executor.mode(), executor)); // highest priority is put last
    }

    public DeploymentExecutor forMode(ExecutionMode mode) {
        DeploymentExecutor executor = byMode.get(mode);
        if (executor == null) {
            throw new IllegalStateException("No executor for mode " + mode);
        }
        return executor;
    }
}
