package com.deployhub.platform.execution;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.worker.DeploymentStepException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * The built-in simulator (Milestones 1-5): nothing is cloned, built or run, each step just takes
 * {@code step-delay}. Kept for the automated tests and for running DeployHub without Docker.
 *
 * <p>Simulated failures, for demos and tests (put the word in the project's build command):
 * <ul>
 *   <li>{@code fail} - permanent build error: deployment FAILED immediately, no retry</li>
 *   <li>{@code flaky} - transient error on attempts 1 and 2, succeeds on attempt 3</li>
 *   <li>{@code unstable} - transient error on every attempt: retries run out, job DEAD</li>
 * </ul>
 */
@Component
public class SimulatedDeploymentExecutor implements DeploymentExecutor {

    private final Duration stepDelay;

    public SimulatedDeploymentExecutor(DeployHubProperties properties) {
        this.stepDelay = properties.worker().stepDelay();
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.SIMULATED;
    }

    @Override
    public BuildResult build(DeploymentSpec spec, ExecutionContext context) {
        pause(context);
        context.info("[simulated] Cloning " + spec.repositoryUrl() + " @ " + spec.ref());
        context.info("[simulated] Running build: " + (spec.buildCommand() != null ? spec.buildCommand() : "(none)"));
        String build = spec.buildCommand() == null ? "" : spec.buildCommand().toLowerCase(Locale.ROOT);
        if (build.contains("unstable") || (build.contains("flaky") && spec.attempt() < 3)) {
            throw DeploymentStepException.transientError("Simulated transient error: package registry timed out");
        }
        if (build.contains("fail")) {
            throw DeploymentStepException.permanent("Simulated build failure: build command exited with code 1");
        }
        context.info("[simulated] Build finished");
        return new BuildResult(null, null);
    }

    @Override
    public RunResult start(DeploymentSpec spec, ExecutionContext context) {
        pause(context);
        context.info("[simulated] Starting: " + (spec.startCommand() != null ? spec.startCommand() : "(image default)"));
        context.info("[simulated] Health check passed");
        return new RunResult(null, null);
    }

    @Override
    public void stop(String containerName) {
        // nothing runs
    }

    /** Sleeps the step delay in small slices, so a cancel takes effect quickly. */
    private void pause(ExecutionContext context) {
        long until = System.nanoTime() + stepDelay.toNanos();
        while (System.nanoTime() < until) {
            context.throwIfCancelled();
            try {
                Thread.sleep(Math.min(100, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Worker interrupted", e);
            }
        }
        context.throwIfCancelled();
    }
}
