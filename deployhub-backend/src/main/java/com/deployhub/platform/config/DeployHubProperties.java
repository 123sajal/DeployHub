package com.deployhub.platform.config;

import com.deployhub.platform.models.enums.ExecutionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "deployhub")
public record DeployHubProperties(Worker worker, Deployments deployments, Security security, Logs logs,
                                  Metrics metrics, Execution execution, Gateway gateway, Secrets secrets,
                                  Databases databases) {

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
     * @param mode                  REAL (Docker) or SIMULATED, for deployments created from now on
     * @param dockerCommand         the docker CLI (on the PATH, or an absolute path)
     * @param gitCommand            the git CLI
     * @param workspace             folder for temporary source checkouts (deleted after each build)
     * @param cloneTimeout          limit for fetching the source
     * @param buildTimeout          limit for docker build
     * @param startTimeout          limit for the container to answer its health check
     * @param portRangeStart        first host port for running apps (published on 127.0.0.1 only)
     * @param portRangeEnd          last host port for running apps
     * @param containerMemory       memory limit per app container (docker --memory)
     * @param containerCpus         CPU limit per app container (docker --cpus)
     * @param containerPidsLimit    process limit per app container (stops fork bombs)
     * @param maxLogLinesPerStep    build/start output kept per step; the rest is summarised in one line
     * @param keepImagesPerProject  images of the last N successful deployments are kept for instant rollback
     */
    public record Execution(ExecutionMode mode, String dockerCommand, String gitCommand, String workspace,
                            Duration cloneTimeout, Duration buildTimeout, Duration startTimeout,
                            int portRangeStart, int portRangeEnd, String containerMemory, String containerCpus,
                            int containerPidsLimit, int maxLogLinesPerStep, int keepImagesPerProject) {
    }

    /**
     * Each project's fixed address: a small proxy container per project, published on 127.0.0.1.
     *
     * @param portRangeStart first port for project addresses
     * @param portRangeEnd   last port for project addresses
     * @param proxyImage     the proxy's Docker image
     * @param tunnelImage    image of the tunnel that gives a project its public link (Cloudflare quick tunnel)
     * @param publicLinkRefresh how often public links are checked (a restarted tunnel gets a new link)
     */
    public record Gateway(int portRangeStart, int portRangeEnd, String proxyImage, String tunnelImage,
                          java.time.Duration publicLinkRefresh) {
    }

    /**
     * The shared MySQL server behind one-click databases.
     *
     * @param mysqlImage    server image
     * @param containerName container name; apps reach it by this name on the deployhub network
     * @param volume        Docker volume holding all databases' data
     * @param hostPort      port on 127.0.0.1 for connecting from this machine (e.g. to create tables)
     * @param memory        memory limit of the server container
     * @param startTimeout  how long the first start (which initialises the server) may take
     */
    public record Databases(String mysqlImage, String containerName, String volume, int hostPort, String memory,
                            Duration startTimeout) {
    }

    /** @param key base64 of 32 random bytes; encrypts environment variable values (AES-256-GCM) */
    public record Secrets(String key) {
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
