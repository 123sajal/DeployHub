package com.deployhub.platform.execution;

import com.deployhub.platform.models.enums.ExecutionMode;

import java.util.Set;

/**
 * Does the actual work of a deployment's BUILDING and STARTING steps. The worker owns the state machine,
 * leases and retries; an executor only builds and runs. Both methods are called outside any database
 * transaction, may take minutes, must be safe to repeat (at-least-once delivery), and signal failure with
 * {@link com.deployhub.platform.worker.DeploymentStepException} (transient = retry, permanent = fail).
 */
public interface DeploymentExecutor {

    /** @param resolvedCommit exact commit SHA built (null when simulated) */
    record BuildResult(String resolvedCommit, String imageRef) {
    }

    /** @param containerName null when nothing really runs (simulated) */
    record RunResult(String containerName, Integer hostPort) {
    }

    /**
     * A path prefix of a project's address that goes to another project's live app.
     *
     * @param containerName the target's live container, or null when it has none (answers 503)
     */
    record Route(String pathPrefix, String containerName, Integer port, String targetName) {
    }

    /**
     * A project's fixed address: {@code http://localhost:publicPort}. Everything not matched by a route goes
     * to the project's own live app ({@code containerName}:{@code port}; null = none, answers 503).
     */
    record Gateway(Long projectId, String projectName, int publicPort, String containerName, Integer port,
                   java.util.List<Route> routes) {
    }

    ExecutionMode mode();

    /** When several executors serve one mode, the highest priority wins (tests plug in fakes this way). */
    default int priority() {
        return 0;
    }

    /** Fetches the source and builds it. */
    BuildResult build(DeploymentSpec spec, ExecutionContext context);

    /** Starts the built app and returns once it answers its health check. Cleans up after itself on failure. */
    RunResult start(DeploymentSpec spec, ExecutionContext context);

    /** Stops and removes a running app. Best effort and idempotent; a null name does nothing. */
    void stop(String containerName);

    /** Removes a project's stored images except {@code keep}. Best effort. */
    default void pruneImages(Long projectId, Set<String> keep) {
    }

    /** Creates or updates the project's fixed address so it points at the given apps. */
    default void publish(Gateway gateway) {
    }

    /** Removes the project's fixed address. Best effort. */
    default void unpublish(Long projectId) {
    }

    /**
     * Gives the project's address a public internet link (starting the tunnel, or adopting a running one) and
     * returns it, waiting until it is known.
     */
    default String startPublicLink(Long projectId) {
        throw new UnsupportedOperationException("Public links need real deployments (Docker)");
    }

    /** The project's current public link, or null if its tunnel isn't running. */
    default String currentPublicLink(Long projectId) {
        return null;
    }

    /** Removes the project's public link. Best effort. */
    default void stopPublicLink(Long projectId) {
    }
}
