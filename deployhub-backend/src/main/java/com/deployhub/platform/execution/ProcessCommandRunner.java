package com.deployhub.platform.execution;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class ProcessCommandRunner implements CommandRunner {

    /** Enough to still contain a build's first error line (npm prints its usage text after it). */
    private static final int TAIL_LINES = 150;

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "command-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public Result run(List<String> command, Map<String, String> env, Path workingDir, Duration timeout,
                      ExecutionContext context, Consumer<String> onLine) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        env.forEach((name, value) -> {
            if (value == null) {
                builder.environment().remove(name); // null = don't pass this variable on at all
            } else {
                builder.environment().put(name, value);
            }
        });
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new StartException(command.getFirst(), e);
        }
        Runnable kill = () -> {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        };
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> timer = watchdog.schedule(() -> {
            timedOut.set(true);
            kill.run();
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        AutoCloseable cancelHook = context == null ? () -> { } : context.onCancel(kill);

        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = output.readLine(); line != null; line = output.readLine()) {
                if (tail.size() == TAIL_LINES) {
                    tail.removeFirst();
                }
                tail.addLast(line);
                onLine.accept(line);
            }
            int exit = process.waitFor();
            if (context != null) {
                context.throwIfCancelled();
            }
            return new Result(timedOut.get() ? -1 : exit, timedOut.get(), new ArrayList<>(tail));
        } catch (IOException e) {
            if (context != null) {
                context.throwIfCancelled(); // reading fails when a cancel killed the process
            }
            throw new IllegalStateException("Lost the output of '" + command.getFirst() + "'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill.run();
            throw new IllegalStateException("Interrupted while running '" + command.getFirst() + "'", e);
        } finally {
            timer.cancel(false);
            try {
                cancelHook.close();
            } catch (Exception ignored) {
                // nothing to clean up
            }
            if (process.isAlive()) {
                kill.run();
            }
        }
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
    }
}
