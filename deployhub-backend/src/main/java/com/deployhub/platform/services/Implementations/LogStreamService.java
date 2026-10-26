package com.deployhub.platform.services.Implementations;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.exceptions.ResourceNotFoundException;
import com.deployhub.platform.exceptions.TooManyRequestsException;
import com.deployhub.platform.mappers.DeploymentMapper;
import com.deployhub.platform.models.entities.DeploymentLog;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.repositories.DeploymentLogRepository;
import com.deployhub.platform.repositories.DeploymentRepository;
import com.deployhub.platform.services.Interface.ICurrentUserService;
import com.deployhub.platform.services.Interface.ILogStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live deployment logs over Server-Sent Events.
 *
 * <p>Every open stream reads new rows from the database on a short timer (default 500 ms) instead of
 * being pushed to in memory: workers can run in other processes, and the database is the one place that
 * sees every log line. The cursor is the log id, sent as each event's {@code id}, so after a dropped
 * connection the browser reconnects with {@code Last-Event-ID} and continues without gaps or duplicates.
 *
 * <p>Events: {@code log} (one line, JSON), {@code status} (deployment status changed) and {@code end}
 * (finished and drained; the server then closes the stream). Idle streams get a comment line every
 * heartbeat interval so proxies don't treat them as dead.
 *
 * <p>Each poll is one short indexed query on a pooled connection, held only for that query. Limits per
 * user and per process keep a burst of open tabs from exhausting the small Oracle pool.
 */
@Slf4j
@Service
public class LogStreamService implements ILogStreamService, SmartLifecycle {

    /** Lines read per query; a bigger backlog is read in several queries within the same poll. */
    private static final int BATCH = 200;

    private final DeploymentRepository deployments;
    private final DeploymentLogRepository logs;
    private final ICurrentUserService currentUserService;
    private final DeployHubProperties.Logs settings;
    private final Clock clock;
    /** Created in start(), shut down in stop(): a context can be stopped and started again (tests do it). */
    private volatile ScheduledExecutorService scheduler;
    private final Set<Stream> open = ConcurrentHashMap.newKeySet();
    private final Map<Long, AtomicInteger> openPerUser = new ConcurrentHashMap<>();
    private volatile boolean running;

    public LogStreamService(DeploymentRepository deployments, DeploymentLogRepository logs,
                            ICurrentUserService currentUserService, DeployHubProperties properties, Clock clock) {
        this.deployments = deployments;
        this.logs = logs;
        this.currentUserService = currentUserService;
        this.settings = properties.logs();
        this.clock = clock;
    }

    @Override
    public SseEmitter open(Long deploymentId, long afterCursor) {
        if (!running) {
            throw new IllegalStateException("Log streaming is stopped (the server is shutting down)");
        }
        Long userId = currentUserService.currentUserId();
        DeploymentStatus status = deployments.findOwned(deploymentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Deployment", deploymentId))
                .getStatus();
        reserveSlot(userId);

        SseEmitter emitter = new SseEmitter(settings.streamTimeout().toMillis());
        Stream stream = new Stream(userId, deploymentId, afterCursor, emitter);
        emitter.onCompletion(stream::close);
        emitter.onTimeout(stream::close);   // the browser reconnects with Last-Event-ID
        emitter.onError(e -> stream.close());
        open.add(stream);
        try {
            // Tell the client the current status straight away, before any (possibly long) backlog.
            stream.sendStatus(status);
            stream.task = scheduler.scheduleWithFixedDelay(stream::poll, 0,
                    settings.streamPollInterval().toMillis(), TimeUnit.MILLISECONDS);
        } catch (IOException | RuntimeException e) {
            stream.close();
            throw new IllegalStateException("Could not start log stream", e);
        }
        return emitter;
    }

    private void reserveSlot(Long userId) {
        if (open.size() >= settings.maxStreams()) {
            throw new TooManyRequestsException("The server is streaming too many logs right now. Try again shortly.",
                    Duration.ofSeconds(10));
        }
        AtomicInteger mine = openPerUser.computeIfAbsent(userId, id -> new AtomicInteger());
        if (mine.incrementAndGet() > settings.maxStreamsPerUser()) {
            mine.decrementAndGet();
            throw new TooManyRequestsException("You have too many log streams open (max "
                    + settings.maxStreamsPerUser() + "). Close a tab and try again.", Duration.ofSeconds(10));
        }
    }

    /** One open SSE connection. Polls never overlap (fixed delay), so no locking is needed inside. */
    private final class Stream {

        private final Long userId;
        private final Long deploymentId;
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean();
        private long cursor;
        private DeploymentStatus lastStatus;
        private Instant lastSentAt;
        private volatile ScheduledFuture<?> task;

        Stream(Long userId, Long deploymentId, long cursor, SseEmitter emitter) {
            this.userId = userId;
            this.deploymentId = deploymentId;
            this.cursor = cursor;
            this.emitter = emitter;
            this.lastSentAt = clock.instant();
        }

        void poll() {
            if (closed.get()) {
                cancelTask(); // closed before the task was assigned in open()
                return;
            }
            try {
                // Status BEFORE logs: a deployment's last lines commit together with (or before) its final
                // status, so "final status, then no new lines" really means the log is complete.
                DeploymentStatus status = deployments.findStatusById(deploymentId).orElse(null);
                if (status == null) { // deleted (its project was removed) while we were watching
                    finish(null);
                    return;
                }
                List<DeploymentLog> rows;
                do { // drain the backlog (e.g. a reconnect after a long gap) in batches
                    rows = logs.findByDeploymentIdAndIdGreaterThanOrderByIdAsc(deploymentId, cursor, Limit.of(BATCH));
                    for (DeploymentLog row : rows) {
                        emitter.send(SseEmitter.event().id(String.valueOf(row.getId())).name("log")
                                .data(DeploymentMapper.toLog(row), MediaType.APPLICATION_JSON));
                        cursor = row.getId();
                        lastSentAt = clock.instant();
                    }
                } while (rows.size() == BATCH && !closed.get());
                if (status != lastStatus) {
                    sendStatus(status);
                }
                if (!status.isInProgress()) {
                    finish(status);
                    return;
                }
                heartbeatIfIdle();
            } catch (IOException | IllegalStateException clientGone) {
                close(); // the browser closed the tab or the connection dropped
            } catch (RuntimeException e) {
                // Usually the database is briefly unreachable: keep the connection and try again next poll.
                log.debug("Log stream for deployment {} could not poll: {}", deploymentId, e.getMessage());
                try {
                    heartbeatIfIdle();
                } catch (IOException | IllegalStateException clientGone) {
                    close();
                }
            }
        }

        void sendStatus(DeploymentStatus status) throws IOException {
            emitter.send(SseEmitter.event().name("status").data(Map.of("status", status), MediaType.APPLICATION_JSON));
            lastStatus = status;
            lastSentAt = clock.instant();
        }

        private void heartbeatIfIdle() throws IOException {
            if (Duration.between(lastSentAt, clock.instant()).compareTo(settings.heartbeatInterval()) >= 0) {
                emitter.send(SseEmitter.event().comment("keep-alive"));
                lastSentAt = clock.instant();
            }
        }

        private void cancelTask() {
            ScheduledFuture<?> current = task;
            if (current != null) {
                current.cancel(false);
            }
        }

        /** Tells the client not to reconnect, then closes the stream. */
        private void finish(DeploymentStatus status) throws IOException {
            emitter.send(SseEmitter.event().name("end")
                    .data(Map.of("status", status == null ? "DELETED" : status.name()), MediaType.APPLICATION_JSON));
            close();
            emitter.complete();
        }

        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            cancelTask();
            open.remove(this);
            AtomicInteger mine = openPerUser.get(userId);
            if (mine != null && mine.decrementAndGet() <= 0) {
                openPerUser.remove(userId, mine);
            }
        }
    }

    // ---- Lifecycle: on shutdown, close streams first so graceful shutdown isn't held up by them. ----

    @Override
    public void start() {
        AtomicInteger threadNumber = new AtomicInteger();
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "log-stream-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        running = true;
    }

    /**
     * Runs before the web server's graceful shutdown (higher phase stops first). Completing each stream
     * makes the browser reconnect, which reaches another instance (or this one once it's back).
     */
    @Override
    public void stop() {
        running = false;
        for (Stream stream : List.copyOf(open)) {
            stream.close();
            try {
                stream.emitter.complete();
            } catch (RuntimeException ignored) {
                // already completed
            }
        }
        scheduler.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Number of open streams in this process (for tests and diagnostics). */
    public int openStreams() {
        return open.size();
    }
}
