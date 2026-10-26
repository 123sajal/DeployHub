package com.deployhub.platform.execution;

import com.deployhub.platform.deployment.DeploymentLogWriter;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a running step can do besides its work: write to the deployment's log (capped, so a chatty build
 * can't fill the database) and notice that it should stop. The worker cancels the context when the user
 * cancels the deployment or the worker loses its lease; registered hooks then kill running processes.
 */
public final class ExecutionContext {

    /** Why a step was stopped from outside. */
    public enum CancelReason { CANCELLED_BY_USER, LEASE_LOST }

    private final Long deploymentId;
    private final DeploymentLogWriter logs;
    private final int maxLines;
    private final AtomicInteger written = new AtomicInteger();
    private final List<Runnable> cancelHooks = new CopyOnWriteArrayList<>();
    private volatile List<String> secrets = List.of();
    private volatile CancelReason cancelReason;

    public ExecutionContext(Long deploymentId, DeploymentLogWriter logs, int maxLines) {
        this.deploymentId = deploymentId;
        this.logs = logs;
        this.maxLines = maxLines;
    }

    public Long deploymentId() {
        return deploymentId;
    }

    /**
     * Values (e.g. environment variables) replaced by *** in everything this context logs, in case a build
     * or the app prints them. Very short values are ignored: masking "1" would garble every line.
     */
    public void maskSecrets(Collection<String> values) {
        secrets = values.stream().filter(v -> v != null && v.length() >= 4)
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    private String masked(String message) {
        if (message == null || secrets.isEmpty()) {
            return message;
        }
        String result = message;
        for (String secret : secrets) {
            result = result.replace(secret, "***");
        }
        return result;
    }

    public void info(String message) {
        logs.info(deploymentId, masked(message));
    }

    public void warn(String message) {
        logs.warn(deploymentId, masked(message));
    }

    public void error(String message) {
        logs.error(deploymentId, masked(message));
    }

    /** One line of build/start output. After maxLines, one notice is written and the rest is dropped. */
    public void output(String line) {
        if (line == null || line.isBlank()) {
            return; // not stored, so it doesn't count towards the limit either
        }
        int n = written.incrementAndGet();
        if (n <= maxLines) {
            logs.info(deploymentId, masked(line));
        } else if (n == maxLines + 1) {
            logs.warn(deploymentId, "... output truncated after " + maxLines + " lines");
        }
    }

    public boolean isCancelled() {
        return cancelReason != null;
    }

    public CancelReason cancelReason() {
        return cancelReason;
    }

    public void cancel(CancelReason reason) {
        if (cancelReason != null) {
            return;
        }
        cancelReason = reason;
        cancelHooks.forEach(Runnable::run);
    }

    /** Runs {@code hook} on cancellation (at once if already cancelled). Close the result to unregister. */
    public AutoCloseable onCancel(Runnable hook) {
        cancelHooks.add(hook);
        if (isCancelled()) {
            hook.run();
        }
        return () -> cancelHooks.remove(hook);
    }

    public void throwIfCancelled() {
        if (cancelReason != null) {
            throw new StepCancelledException(cancelReason);
        }
    }
}
