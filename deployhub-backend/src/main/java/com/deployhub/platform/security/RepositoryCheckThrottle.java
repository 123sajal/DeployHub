package com.deployhub.platform.security;

import com.deployhub.platform.exceptions.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits how often one user can make DeployHub contact a Git host (Detect from repository, checking an access token):
 * each check is a network request, and token checks must not become a free service for testing stolen tokens.
 * In memory, per backend process.
 */
@Component
public class RepositoryCheckThrottle {

    static final int MAX_PER_MINUTE = 20;

    private final Clock clock;
    private final Map<Long, Deque<Instant>> recent = new ConcurrentHashMap<>();

    public RepositoryCheckThrottle(Clock clock) {
        this.clock = clock;
    }

    /** @throws TooManyRequestsException after MAX_PER_MINUTE checks within a minute */
    public void check(Long userId) {
        Instant now = clock.instant();
        Deque<Instant> times = recent.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(Duration.ofMinutes(1)))) {
                times.pollFirst();
            }
            if (times.size() >= MAX_PER_MINUTE) {
                throw new TooManyRequestsException("Too many repository checks. Wait a minute.", Duration.ofMinutes(1));
            }
            times.addLast(now);
        }
    }
}
