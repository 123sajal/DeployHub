package com.deployhub.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "deployhub")
public record DeployHubProperties(Worker worker, Deployments deployments, Security security, Logs logs,
                                  Metrics metrics) {

    /**
     * @param enabled          run deployment workers inside this process (API-only nodes can switch it off)
     * @param id               worker id written into claimed jobs; blank = hostname + random suffix
     * @param pollInterval     how often an idle worker checks the queue
     * @param maxConcurrentJobs jobs one process runs at the same time (backpressure on CPU/DB connections)
     * @param leaseDuration    how long a claim stays valid without a heartbeat before others may take over
     * @param stepDelay        simulated duration of each deployment step
     * @param maxAttempts      attempts per job before it is marked DEAD
     * @param retryBaseDelay   first retry delay; doubles each attempt (with jitter)
     * @param retryMaxDelay    upper bound for the retry delay
     */
    public record Worker(boolean enabled, String id, Duration pollInterval, int maxConcurrentJobs,
                         Duration leaseDuration, Duration stepDelay, int maxAttempts, Duration retryBaseDelay,
                         Duration retryMaxDelay) {
    }

    /** @param maxActivePerUser admission control: queued + running deployments one user may have */
    public record Deployments(int maxActivePerUser) {
    }

    /**
     * @param streamPollInterval how often an open log stream checks for new lines
     * @param streamTimeout      a stream is closed after this long; the browser reconnects from its last line
     * @param heartbeatInterval  idle streams get a comment line this often, so proxies don't cut them
     * @param maxStreamsPerUser  open log streams one user may have (e.g. browser tabs)
     * @param maxStreams         open log streams per backend process
     */
    public record Logs(Duration streamPollInterval, Duration streamTimeout, Duration heartbeatInterval,
                       int maxStreamsPerUser, int maxStreams) {
    }

    /**
     * @param allowedIps           networks (CIDR) allowed to read /actuator/prometheus
     * @param queueRefreshInterval how often the queue-depth gauges are re-read from the database
     */
    public record Metrics(List<String> allowedIps, Duration queueRefreshInterval) {
    }

    /**
     * @param cookieSecure          mark session/CSRF cookies Secure (must be true behind HTTPS)
     * @param loginWindow           window over which failed logins are counted
     * @param maxFailuresPerAccount failed logins allowed per (email, IP) within the window
     * @param maxFailuresPerIp      failed logins allowed per IP within the window
     * @param registrationWindow    window over which registrations are counted
     * @param maxRegistrationsPerIp registrations allowed per IP within that window
     */
    public record Security(boolean cookieSecure, Duration loginWindow, int maxFailuresPerAccount, int maxFailuresPerIp,
                           Duration registrationWindow, int maxRegistrationsPerIp) {
    }
}
