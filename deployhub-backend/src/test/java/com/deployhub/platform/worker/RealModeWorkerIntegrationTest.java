package com.deployhub.platform.worker;

import com.deployhub.platform.execution.DeploymentExecutor;
import com.deployhub.platform.execution.DeploymentExecutor.Gateway;
import com.deployhub.platform.execution.DeploymentSpec;
import com.deployhub.platform.execution.ExecutionContext;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.models.enums.JobStatus;
import com.deployhub.platform.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The worker in REAL mode, with Docker replaced by a fake executor: what the worker records, how it keeps
 * the lease during a long build, how a cancel stops running work, and what it cleans up.
 */
@Import(RealModeWorkerIntegrationTest.FakeDockerConfig.class)
@TestPropertySource(properties = {"deployhub.execution.mode=REAL", "deployhub.worker.lease-duration=1s"})
class RealModeWorkerIntegrationTest extends IntegrationTestSupport {

    /** Stands in for Docker: builds "take" {@code buildTime}, every build of a ref gets a new commit SHA. */
    static class FakeDocker implements DeploymentExecutor {

        final List<String> builtRefs = Collections.synchronizedList(new ArrayList<>());
        final List<String> stopped = Collections.synchronizedList(new ArrayList<>());
        final List<Set<String>> pruned = Collections.synchronizedList(new ArrayList<>());
        final List<Gateway> published = Collections.synchronizedList(new ArrayList<>());
        final List<Long> unpublished = Collections.synchronizedList(new ArrayList<>());
        final java.util.Map<Long, String> tunnels = new java.util.concurrent.ConcurrentHashMap<>();
        final List<Long> tunnelsStopped = Collections.synchronizedList(new ArrayList<>());
        final List<java.util.Map<String, String>> environments = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger ports = new AtomicInteger(20000);
        final AtomicBoolean sawCancel = new AtomicBoolean();
        volatile Duration buildTime = Duration.ZERO;
        volatile boolean blockUntilCancelled;
        volatile CountDownLatch buildStarted = new CountDownLatch(1);

        void reset() {
            builtRefs.clear();
            stopped.clear();
            pruned.clear();
            published.clear();
            unpublished.clear();
            tunnels.clear();
            tunnelsStopped.clear();
            environments.clear();
            sawCancel.set(false);
            buildTime = Duration.ZERO;
            blockUntilCancelled = false;
            buildStarted = new CountDownLatch(1);
        }

        @Override
        public ExecutionMode mode() {
            return ExecutionMode.REAL;
        }

        @Override
        public int priority() {
            return 100; // wins over the real Docker executor
        }

        @Override
        public BuildResult build(DeploymentSpec spec, ExecutionContext context) {
            builtRefs.add(spec.ref());
            context.info("fake build of " + spec.ref());
            buildStarted.countDown();
            long until = System.nanoTime() + (blockUntilCancelled ? Duration.ofSeconds(20) : buildTime).toNanos();
            while (System.nanoTime() < until) {
                if (context.isCancelled()) {
                    sawCancel.set(true);
                }
                context.throwIfCancelled();
                sleep(20);
            }
            String sha = spec.ref().matches("[0-9a-f]{40}") ? spec.ref()
                    : "%02x".formatted(commits.incrementAndGet() % 256).repeat(20);
            return new BuildResult(sha, "deployhub/p" + spec.projectId() + ":" + sha.substring(0, 12) + "-test");
        }

        @Override
        public RunResult start(DeploymentSpec spec, ExecutionContext context) {
            context.info("fake start of " + spec.imageRef());
            environments.add(spec.environment());
            spec.environment().values().forEach(value -> context.output("app printed " + value));
            return new RunResult("deployhub-d" + spec.deploymentId(), ports.incrementAndGet());
        }

        @Override
        public void stop(String containerName) {
            if (containerName != null) {
                stopped.add(containerName);
            }
        }

        @Override
        public void pruneImages(Long projectId, Set<String> keep) {
            pruned.add(keep);
        }

        @Override
        public void publish(Gateway gateway) {
            published.add(gateway);
        }

        @Override
        public void unpublish(Long projectId) {
            unpublished.add(projectId);
        }

        @Override
        public String startPublicLink(Long projectId) {
            return tunnels.computeIfAbsent(projectId, id -> "https://project-" + id + ".trycloudflare.com");
        }

        @Override
        public String currentPublicLink(Long projectId) {
            return tunnels.get(projectId);
        }

        @Override
        public void stopPublicLink(Long projectId) {
            tunnels.remove(projectId);
            tunnelsStopped.add(projectId);
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @TestConfiguration
    static class FakeDockerConfig {
        @Bean
        FakeDocker fakeDocker() {
            return new FakeDocker();
        }
    }

    @Autowired DeploymentJobWorker worker;
    @Autowired FakeDocker docker;

    @BeforeEach
    void resetFake() {
        docker.reset();
    }

    private String projectUrl(long projectId) {
        return "http://localhost:" + projectRepository.findById(projectId).orElseThrow().getPublicPort();
    }

    private DeploymentStatus statusOf(long id) {
        return deploymentRepository.findById(id).orElseThrow().getStatus();
    }

    @Test
    void aRealDeploymentRecordsItsCommitImageAndAddress() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        worker.runAvailableJobs(10);

        var deployment = deploymentRepository.findById(id).orElseThrow();
        assertThat(deployment.getStatus()).isEqualTo(DeploymentStatus.LIVE);
        assertThat(deployment.getResolvedCommit()).hasSize(40);
        assertThat(deployment.getImageRef()).startsWith("deployhub/p");
        assertThat(deployment.getContainerName()).isEqualTo("deployhub-d" + id);
        mockMvc.perform(get("/api/deployments/{id}", id))
                .andExpect(jsonPath("$.executionMode").value("REAL"))
                .andExpect(jsonPath("$.resolvedCommit").value(deployment.getResolvedCommit()))
                .andExpect(jsonPath("$.appUrl").value(projectUrl(deployment.getProject().getId())));
        assertThat(deploymentLogRepository.findAll()).filteredOn(l -> l.getDeploymentId() == id)
                .extracting(l -> l.getMessage())
                .anyMatch(m -> m.equals("Worker test-worker picked up the deployment"))
                .anyMatch(m -> m.startsWith("Deployment is LIVE (this deployment directly: http://localhost:"))
                .anyMatch(m -> m.startsWith("Project address http://localhost:19"))
                .noneMatch(m -> m.contains("[simulated]"));
        mockMvc.perform(get("/api/health")).andExpect(jsonPath("$.deploymentExecutionMode").value("REAL"));
    }

    @Test
    void replacingADeploymentStopsTheOldContainerAndKeepsRecentImages() throws Exception {
        long projectId = createProject("api", "npm ci");
        long first = createDeployment(projectId);
        worker.runAvailableJobs(10);
        long second = createDeployment(projectId);
        worker.runAvailableJobs(10);

        assertThat(statusOf(first)).isEqualTo(DeploymentStatus.SUPERSEDED);
        assertThat(docker.stopped).containsExactly("deployhub-d" + first);
        assertThat(docker.pruned.getLast()).containsExactlyInAnyOrder(
                deploymentRepository.findById(first).orElseThrow().getImageRef(),
                deploymentRepository.findById(second).orElseThrow().getImageRef());
        mockMvc.perform(get("/api/deployments/{id}", first)).andExpect(jsonPath("$.appUrl").doesNotExist());
        mockMvc.perform(get("/api/deployments/{id}", second)).andExpect(jsonPath("$.appUrl", startsWith("http://")));
    }

    @Test
    void aLongBuildKeepsTheLeaseSoNoOtherWorkerTakesTheJobOver() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        docker.buildTime = Duration.ofMillis(2500); // the lease is only 1 s
        DeploymentJobWorker.ClaimedJob job = worker.claimNext().orElseThrow();
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> worker.execute(job));

        assertThat(docker.buildStarted.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(1500); // past the original lease
        assertThat(jobRepository.findClaimableIds(Instant.now(), Limit.of(10)))
                .as("the lease was renewed while the build ran").isEmpty();

        running.get(10, TimeUnit.SECONDS);
        assertThat(statusOf(id)).isEqualTo(DeploymentStatus.LIVE);
        assertThat(jobRepository.findByDeploymentId(id).orElseThrow().getAttempt()).isEqualTo(1);
    }

    @Test
    void cancellingStopsARunningBuildWithinSeconds() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        docker.blockUntilCancelled = true;
        DeploymentJobWorker.ClaimedJob job = worker.claimNext().orElseThrow();
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> worker.execute(job));
        assertThat(docker.buildStarted.await(5, TimeUnit.SECONDS)).isTrue();

        mockMvc.perform(post("/api/deployments/{id}/cancel", id)).andExpect(status().isOk());

        running.get(5, TimeUnit.SECONDS);
        assertThat(docker.sawCancel).isTrue();
        assertThat(statusOf(id)).isEqualTo(DeploymentStatus.CANCELLED);
        assertThat(jobRepository.findByDeploymentId(id).orElseThrow().getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void rollbackRedeploysTheExactCommitThatWasBuilt() throws Exception {
        long projectId = createProject("api", "npm ci");
        long first = createDeployment(projectId);
        worker.runAvailableJobs(10);
        createDeployment(projectId);
        worker.runAvailableJobs(10);
        String firstCommit = deploymentRepository.findById(first).orElseThrow().getResolvedCommit();

        long rollback = idFrom(mockMvc.perform(post("/api/deployments/{id}/rollback", first))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commitRef").value(firstCommit))
                .andReturn());
        worker.runAvailableJobs(10);

        assertThat(docker.builtRefs.getLast()).isEqualTo(firstCommit);
        assertThat(deploymentRepository.findById(rollback).orElseThrow().getResolvedCommit()).isEqualTo(firstCommit);
        assertThat(statusOf(rollback)).isEqualTo(DeploymentStatus.LIVE);
        mockMvc.perform(get("/api/deployments/{id}/logs", rollback))
                .andExpect(jsonPath("$.items[0].message", startsWith("Rollback queued")));
    }

    @Test
    void deletingAProjectStopsItsLiveAppAndRemovesItsImages() throws Exception {
        long projectId = createProject("api", "npm ci");
        long id = createDeployment(projectId);
        worker.runAvailableJobs(10);
        docker.pruned.clear();

        mockMvc.perform(delete("/api/projects/{id}", projectId)).andExpect(status().isNoContent());

        assertThat(docker.stopped).contains("deployhub-d" + id);
        assertThat(docker.pruned).containsExactly(Set.of());
    }

    @Test
    void deletingAReplacedDeploymentDropsItsImageButKeepsTheOthers() throws Exception {
        long projectId = createProject("api", "npm ci");
        long first = createDeployment(projectId);
        worker.runAvailableJobs(10);
        long second = createDeployment(projectId);
        worker.runAvailableJobs(10);
        String secondImage = deploymentRepository.findById(second).orElseThrow().getImageRef();
        docker.pruned.clear();

        mockMvc.perform(delete("/api/deployments/{id}", first)).andExpect(status().isNoContent());

        assertThat(docker.pruned).containsExactly(Set.of(secondImage));
    }

    @Test
    void environmentVariablesReachTheAppAndAreMaskedInItsLogs() throws Exception {
        long projectId = createProject("api", "npm ci");
        mockMvc.perform(put("/api/projects/{id}/env/{name}", projectId, "DB_PASSWORD")
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"hunter2-db-pass\"}"))
                .andExpect(status().isOk());
        long id = createDeployment(projectId);
        worker.runAvailableJobs(10);

        assertThat(docker.environments.getLast()).containsEntry("DB_PASSWORD", "hunter2-db-pass");
        assertThat(deploymentLogRepository.findAll()).filteredOn(l -> l.getDeploymentId() == id)
                .extracting(l -> l.getMessage())
                .contains("app printed ***")
                .noneMatch(m -> m.contains("hunter2"));
    }

    @Test
    void goingLiveGivesTheProjectAFixedAddressThatSurvivesRedeploys() throws Exception {
        long projectId = createProject("api", "npm ci");
        createDeployment(projectId);
        worker.runAvailableJobs(10);
        String url = projectUrl(projectId);
        Gateway first = docker.published.getLast();

        long second = createDeployment(projectId);
        worker.runAvailableJobs(10);

        assertThat(projectUrl(projectId)).isEqualTo(url).startsWith("http://localhost:19");
        Gateway latest = docker.published.getLast();
        assertThat(latest.publicPort()).isEqualTo(first.publicPort());
        assertThat(latest.containerName()).isEqualTo("deployhub-d" + second);
        assertThat(latest.port()).isEqualTo(3000);
        mockMvc.perform(get("/api/projects/{id}", projectId)).andExpect(jsonPath("$.localUrl").value(url));
        mockMvc.perform(get("/api/deployments/{id}", second)).andExpect(jsonPath("$.appUrl").value(url));
    }

    @Test
    void routesSendAPathToAnotherProjectAndFollowItsRedeploys() throws Exception {
        long web = createProject("web", "npm ci");
        long api = createProject("api", "npm ci");
        createDeployment(web);
        createDeployment(api);
        worker.runAvailableJobs(10);

        mockMvc.perform(put("/api/projects/{id}/routes", web).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routes\":[{\"pathPrefix\":\"/api\",\"targetProjectId\":" + api + "}]}"))
                .andExpect(status().isOk());
        Gateway afterRoutes = docker.published.getLast();
        assertThat(afterRoutes.projectId()).isEqualTo(web);
        assertThat(afterRoutes.routes()).singleElement().satisfies(r -> {
            assertThat(r.pathPrefix()).isEqualTo("/api");
            assertThat(r.containerName()).startsWith("deployhub-d");
        });

        long apiV2 = createDeployment(api);
        docker.published.clear();
        worker.runAvailableJobs(10);

        assertThat(docker.published).extracting(Gateway::projectId).containsExactly(api, web);
        assertThat(docker.published.getLast().routes().getFirst().containerName()).isEqualTo("deployhub-d" + apiV2);
    }

    @Test
    void deletingARouteTargetRemovesItsAddressAndUpdatesTheProjectsThatUsedIt() throws Exception {
        long web = createProject("web", "npm ci");
        long api = createProject("api", "npm ci");
        createDeployment(web);
        createDeployment(api);
        worker.runAvailableJobs(10);
        mockMvc.perform(put("/api/projects/{id}/routes", web).contentType(MediaType.APPLICATION_JSON)
                .content("{\"routes\":[{\"pathPrefix\":\"/api\",\"targetProjectId\":" + api + "}]}"));
        docker.published.clear();

        mockMvc.perform(delete("/api/projects/{id}", api)).andExpect(status().isNoContent());

        assertThat(docker.unpublished).containsExactly(api);
        assertThat(docker.published).singleElement().satisfies(g -> {
            assertThat(g.projectId()).isEqualTo(web);
            assertThat(g.routes()).isEmpty();
        });
    }

    @Autowired com.deployhub.platform.services.Implementations.ProjectPublicLinkService publicLinks;

    @Test
    void onlyALiveProjectCanGetAPublicLink() throws Exception {
        long projectId = createProject("web", "npm ci");

        mockMvc.perform(post("/api/projects/{id}/public-link", projectId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Deploy web first: only a live app can get a public link"));
        mockMvc.perform(get("/api/projects/{id}/public-link", projectId))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.url").doesNotExist());
    }

    @Test
    void openAppUsesThePublicLinkWhileItIsOnAndTheLocalAddressOtherwise() throws Exception {
        long projectId = createProject("web", "npm ci");
        long id = createDeployment(projectId);
        worker.runAvailableJobs(10);
        String local = projectUrl(projectId);

        mockMvc.perform(post("/api/projects/{id}/public-link", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.url").value("https://project-" + projectId + ".trycloudflare.com"))
                .andExpect(jsonPath("$.localUrl").value(local));
        mockMvc.perform(get("/api/deployments/{id}", id))
                .andExpect(jsonPath("$.appUrl").value("https://project-" + projectId + ".trycloudflare.com"));
        mockMvc.perform(get("/api/projects/{id}", projectId))
                .andExpect(jsonPath("$.publicLinkEnabled").value(true))
                .andExpect(jsonPath("$.publicLink").value("https://project-" + projectId + ".trycloudflare.com"))
                .andExpect(jsonPath("$.localUrl").value(local));

        mockMvc.perform(delete("/api/projects/{id}/public-link", projectId))
                .andExpect(jsonPath("$.enabled").value(false));
        assertThat(docker.tunnelsStopped).containsExactly(projectId);
        mockMvc.perform(get("/api/deployments/{id}", id)).andExpect(jsonPath("$.appUrl").value(local));
    }

    @Test
    void aRestartedTunnelsNewLinkIsPickedUpAndAMissingTunnelIsStartedAgain() throws Exception {
        long projectId = createProject("web", "npm ci");
        createDeployment(projectId);
        worker.runAvailableJobs(10);
        mockMvc.perform(post("/api/projects/{id}/public-link", projectId));

        docker.tunnels.put(projectId, "https://after-restart.trycloudflare.com"); // e.g. Docker restarted
        mockMvc.perform(get("/api/projects/{id}/public-link", projectId))
                .andExpect(jsonPath("$.url").value("https://after-restart.trycloudflare.com"));

        docker.tunnels.clear(); // the tunnel container disappeared
        publicLinks.refreshAll();
        assertThat(docker.tunnels).containsKey(projectId);
        mockMvc.perform(get("/api/projects/{id}", projectId))
                .andExpect(jsonPath("$.publicLink").value("https://project-" + projectId + ".trycloudflare.com"));
    }

    @Test
    void deletingAProjectRemovesItsPublicLink() throws Exception {
        long projectId = createProject("web", "npm ci");
        createDeployment(projectId);
        worker.runAvailableJobs(10);
        mockMvc.perform(post("/api/projects/{id}/public-link", projectId));

        mockMvc.perform(delete("/api/projects/{id}", projectId)).andExpect(status().isNoContent());

        assertThat(docker.tunnelsStopped).contains(projectId);
    }

    @Test
    void aDockerfileProjectNeedsNoStartCommandButOthersDo() throws Exception {
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"own-image","repositoryUrl":"https://github.com/acme/own-image","branch":"main",
                         "appType":"DOCKERFILE","appPort":80,"healthCheckPath":"/healthz"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startCommand").doesNotExist())
                .andExpect(jsonPath("$.effectivePort").value(80))
                .andExpect(jsonPath("$.healthCheckPath").value("/healthz"));
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"node-app","repositoryUrl":"https://github.com/acme/node-app","branch":"main",
                         "appType":"NODE_JS","startCommand":"  ","buildCommand":"npm ci\\nnpm test"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.startCommand").value("must not be blank"))
                .andExpect(jsonPath("$.errors.buildCommand").value("must be a single line"));
    }
}
