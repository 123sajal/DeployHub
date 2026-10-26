package com.deployhub.platform.worker;

import com.deployhub.platform.deployment.DeploymentLogWriter;
import com.deployhub.platform.execution.ExecutionContext;
import com.deployhub.platform.models.entities.Deployment;
import com.deployhub.platform.models.entities.DeploymentJob;
import com.deployhub.platform.models.entities.DeploymentStatusChange;
import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.models.enums.JobStatus;
import com.deployhub.platform.models.enums.LogLevel;
import com.deployhub.platform.services.Interface.IDeploymentService;
import com.deployhub.platform.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeploymentLifecycleIntegrationTest extends IntegrationTestSupport {

    @Autowired DeploymentJobWorker worker;
    @Autowired IDeploymentService deploymentService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DeploymentLogWriter logWriter;

    private DeploymentStatus statusOf(long id) {
        return deploymentRepository.findById(id).orElseThrow().getStatus();
    }

    private DeploymentJob jobOf(long deploymentId) {
        return jobRepository.findByDeploymentId(deploymentId).orElseThrow();
    }

    private List<DeploymentStatus> pathOf(long deploymentId) {
        return statusHistoryRepository.findByDeploymentIdOrderByIdAsc(deploymentId).stream()
                .map(DeploymentStatusChange::getToStatus).toList();
    }

    private int runAll() {
        return worker.runAvailableJobs(100);
    }

    @Test
    void newDeploymentIsQueuedWithAJobInTheSameTransaction() throws Exception {
        long projectId = createProject("api", "npm ci");

        long id = idFrom(mockMvc.perform(post("/api/projects/{id}/deployments", projectId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.executionMode").value("SIMULATED"))
                .andExpect(jsonPath("$.cancellable").value(true))
                .andReturn());

        DeploymentJob job = jobOf(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getAttempt()).isZero();
        assertThat(job.getMaxAttempts()).isEqualTo(3);
        assertThat(pathOf(id)).containsExactly(DeploymentStatus.QUEUED);
    }

    @Test
    void workerRunsTheJobThroughEveryStateAndRecordsHistory() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));

        assertThat(runAll()).isEqualTo(1);

        assertThat(pathOf(id)).containsExactly(DeploymentStatus.QUEUED, DeploymentStatus.BUILDING,
                DeploymentStatus.STARTING, DeploymentStatus.LIVE);
        assertThat(statusHistoryRepository.findByDeploymentIdOrderByIdAsc(id)).extracting(DeploymentStatusChange::getActor)
                .containsExactly("user:" + alice.id(), "worker:test-worker", "worker:test-worker", "worker:test-worker");
        Deployment live = deploymentRepository.findById(id).orElseThrow();
        assertThat(live.getStartedAt()).isNotNull();
        assertThat(live.getFinishedAt()).isNotNull();
        DeploymentJob job = jobOf(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getAttempt()).isEqualTo(1);
        assertThat(job.getLockedBy()).isNull();
        assertThat(runAll()).as("nothing left to do").isZero();
    }

    @Test
    void newLiveDeploymentSupersedesPreviousOne() throws Exception {
        long projectId = createProject("api", "npm ci");
        long first = createDeployment(projectId);
        runAll();
        Instant firstWentLive = deploymentRepository.findById(first).orElseThrow().getFinishedAt();
        long second = createDeployment(projectId);
        runAll();

        assertThat(statusOf(first)).isEqualTo(DeploymentStatus.SUPERSEDED);
        assertThat(statusOf(second)).isEqualTo(DeploymentStatus.LIVE);
        assertThat(statusHistoryRepository.findByDeploymentIdOrderByIdAsc(first).getLast().getReason())
                .isEqualTo("Replaced by deployment #" + second);
        assertThat(deploymentRepository.findById(first).orElseThrow().getFinishedAt())
                .as("being superseded must not overwrite when it went live").isEqualTo(firstWentLive);
    }

    @Test
    void olderDeploymentFinishingLateNeverReplacesNewerLiveOne() throws Exception {
        long projectId = createProject("api", "npm ci");
        long older = createDeployment(projectId);
        DeploymentJobWorker.ClaimedJob slow = worker.claimNext().orElseThrow(); // older one is claimed but slow
        long newer = createDeployment(projectId);
        assertThat(runAll()).isEqualTo(1);
        assertThat(statusOf(newer)).isEqualTo(DeploymentStatus.LIVE);

        worker.execute(slow); // the older deployment now reaches its go-live step

        assertThat(statusOf(newer)).as("newer code stays live").isEqualTo(DeploymentStatus.LIVE);
        Deployment skipped = deploymentRepository.findById(older).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo(DeploymentStatus.CANCELLED);
        assertThat(skipped.getFailureReason()).isEqualTo("Skipped: newer deployment #" + newer + " is already live");
        assertThat(pathOf(older)).containsExactly(DeploymentStatus.QUEUED, DeploymentStatus.BUILDING,
                DeploymentStatus.STARTING, DeploymentStatus.CANCELLED);
        assertThat(jobOf(older).getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(jobOf(older).getLockedBy()).isNull();
    }

    @Test
    void permanentFailureIsNotRetried() throws Exception {
        long id = createDeployment(createProject("broken", "npm run fail-on-purpose"));
        runAll();

        mockMvc.perform(get("/api/deployments/{id}", id))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value(containsString("Simulated build failure")))
                .andExpect(jsonPath("$.cancellable").value(false));
        DeploymentJob job = jobOf(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAttempt()).as("no retries for a permanent error").isEqualTo(1);
        assertThat(deploymentLogRepository.findAll())
                .anyMatch(l -> l.getDeploymentId() == id && l.getLevel() == LogLevel.ERROR);
    }

    @Test
    void transientFailuresAreRetriedAndThenSucceed() throws Exception {
        long id = createDeployment(createProject("flaky-app", "npm run flaky-build"));

        assertThat(runAll()).as("attempts 1, 2 and 3").isEqualTo(3);

        assertThat(statusOf(id)).isEqualTo(DeploymentStatus.LIVE);
        DeploymentJob job = jobOf(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getAttempt()).isEqualTo(3);
        assertThat(job.getLastError()).contains("transient");
        assertThat(deploymentLogRepository.findAll()).filteredOn(l -> l.getDeploymentId() == id)
                .extracting(l -> l.getMessage())
                .anyMatch(m -> m.startsWith("Attempt 1 of 3 failed"))
                .anyMatch(m -> m.startsWith("Attempt 3 of 3"));
        // The retries resumed BUILDING; they did not start the deployment over.
        assertThat(pathOf(id)).containsExactly(DeploymentStatus.QUEUED, DeploymentStatus.BUILDING,
                DeploymentStatus.STARTING, DeploymentStatus.LIVE);
    }

    @Test
    void exhaustedRetriesMarkTheJobDeadAndTheDeploymentFailed() throws Exception {
        long id = createDeployment(createProject("never-works", "npm run unstable"));

        assertThat(runAll()).isEqualTo(3);
        assertThat(runAll()).as("a dead job is never picked up again").isZero();

        DeploymentJob job = jobOf(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD);
        assertThat(job.getAttempt()).isEqualTo(3);
        Deployment deployment = deploymentRepository.findById(id).orElseThrow();
        assertThat(deployment.getStatus()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(deployment.getFailureReason()).startsWith("Gave up after 3 attempts: Simulated transient error");

        mockMvc.perform(get("/api/deployments/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("DEAD"))
                .andExpect(jsonPath("$.job.attempt").value(3))
                .andExpect(jsonPath("$.job.maxAttempts").value(3))
                .andExpect(jsonPath("$.transitions[-1].toStatus").value("FAILED"))
                .andExpect(jsonPath("$.transitions[-1].reason").value(containsString("Gave up after 3 attempts")));
    }

    @Test
    void cancellingAQueuedDeploymentDropsItsJob() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));

        mockMvc.perform(post("/api/deployments/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.failureReason").value("Cancelled by user"));
        mockMvc.perform(post("/api/deployments/{id}/cancel", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Cannot change deployment status from CANCELLED to CANCELLED"));

        assertThat(jobOf(id).getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(runAll()).isZero();
        assertThat(statusOf(id)).isEqualTo(DeploymentStatus.CANCELLED);
    }

    @Test
    void cancellingWhileARetryIsScheduledStopsTheRetry() throws Exception {
        long id = createDeployment(createProject("never-works", "npm run unstable"));
        worker.execute(worker.claimNext().orElseThrow()); // attempt 1 fails, retry scheduled
        assertThat(jobOf(id).getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(statusOf(id)).isEqualTo(DeploymentStatus.BUILDING);

        mockMvc.perform(post("/api/deployments/{id}/cancel", id)).andExpect(status().isOk());

        assertThat(runAll()).isZero();
        assertThat(jobOf(id).getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(statusOf(id)).isEqualTo(DeploymentStatus.CANCELLED);
    }

    @Test
    void cancelledWhileClaimedTheWorkerStopsAtItsNextStep() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        DeploymentJobWorker.ClaimedJob claimed = worker.claimNext().orElseThrow(); // claimed, not started yet

        mockMvc.perform(post("/api/deployments/{id}/cancel", id)).andExpect(status().isOk());
        worker.execute(claimed);

        assertThat(statusOf(id)).isEqualTo(DeploymentStatus.CANCELLED);
        assertThat(jobOf(id).getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(pathOf(id)).containsExactly(DeploymentStatus.QUEUED, DeploymentStatus.CANCELLED);
    }

    @Test
    void liveDeploymentCannotBeCancelled() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        runAll();

        mockMvc.perform(post("/api/deployments/{id}/cancel", id)).andExpect(status().isConflict());
    }

    /**
     * The invariant: a worker holding a stale copy of a deployment must not overwrite a
     * cancellation that committed after it read the row. The @Version column turns that
     * lost update into an OptimisticLockingFailureException.
     */
    @Test
    void staleWorkerWriteCannotOverwriteConcurrentCancel() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        TransactionTemplate workerTx = new TransactionTemplate(transactionManager);
        TransactionTemplate userTx = new TransactionTemplate(transactionManager);
        userTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        actAs(alice); // the direct service call below runs as Alice, like an HTTP request would
        assertThatThrownBy(() -> workerTx.executeWithoutResult(s -> {
            Deployment stale = deploymentRepository.findById(id).orElseThrow();   // worker reads QUEUED, version 0
            userTx.executeWithoutResult(u -> deploymentService.cancel(id));       // user cancels and commits, version 1
            stale.transitionTo(DeploymentStatus.BUILDING, Instant.now(), null);
            deploymentRepository.saveAndFlush(stale);                            // UPDATE ... WHERE version = 0
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(statusOf(id)).isEqualTo(DeploymentStatus.CANCELLED);
    }

    @Test
    void historyEndpointShowsEveryTransition() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        runAll();

        mockMvc.perform(get("/api/deployments/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.job.attempt").value(1))
                .andExpect(jsonPath("$.transitions", hasSize(4)))
                .andExpect(jsonPath("$.transitions[0].fromStatus").doesNotExist())
                .andExpect(jsonPath("$.transitions[0].toStatus").value("QUEUED"))
                .andExpect(jsonPath("$.transitions[3].fromStatus").value("STARTING"))
                .andExpect(jsonPath("$.transitions[3].toStatus").value("LIVE"))
                .andExpect(jsonPath("$.transitions[3].actor").value("worker:test-worker"));
    }

    @Test
    void logsArePagedWithCursorWithoutGapsOrDuplicates() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        runAll();
        long total = deploymentLogRepository.findAll().stream().filter(l -> l.getDeploymentId() == id).count();
        assertThat(total).isGreaterThan(4);

        List<Long> ids = new ArrayList<>();
        long cursor = 0;
        boolean hasMore = true;
        while (hasMore) {
            var result = mockMvc.perform(get("/api/deployments/{id}/logs", id)
                            .param("after", String.valueOf(cursor)).param("limit", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deploymentStatus").value("LIVE"))
                    .andReturn().getResponse().getContentAsString();
            var idMatcher = java.util.regex.Pattern.compile("\\{\"id\":(\\d+)").matcher(result);
            while (idMatcher.find()) ids.add(Long.parseLong(idMatcher.group(1)));
            cursor = Long.parseLong(result.replaceAll(".*\"nextCursor\":(\\d+).*", "$1"));
            hasMore = result.contains("\"hasMore\":true");
        }

        assertThat(ids).hasSize((int) total).isSorted();
        assertThat(new HashSet<>(ids)).hasSize(ids.size());

        // Reconnecting from the last cursor returns nothing new and keeps the cursor.
        mockMvc.perform(get("/api/deployments/{id}/logs", id).param("after", String.valueOf(cursor)))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").value(cursor))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    /**
     * Regression: docker build prints empty lines, and Oracle stores '' as NULL, which the NOT NULL message
     * column rejected (every real build failed on Oracle). Blank lines are now skipped.
     */
    @Test
    void blankLogLinesAreSkippedInsteadOfBreakingTheDeployment() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        long before = deploymentLogRepository.count();
        ExecutionContext context = new ExecutionContext(id, logWriter, 100);

        logWriter.info(id, "");
        logWriter.warn(id, "   ");
        logWriter.error(id, null);
        context.output("");
        context.output("\t");
        context.output("real line");

        assertThat(deploymentLogRepository.count()).isEqualTo(before + 1);
        assertThat(deploymentLogRepository.findAll()).anyMatch(l -> l.getMessage().equals("real line"));
    }

    @Test
    void secretsInCommandsAreMaskedInLogs() throws Exception {
        long id = createDeployment(createProject("api", "API_TOKEN=supersecret123 npm run build"));
        runAll();

        mockMvc.perform(get("/api/deployments/{id}/logs", id))
                .andExpect(jsonPath("$.items[*].message").value(org.hamcrest.Matchers.hasItem(containsString("API_TOKEN=***"))))
                .andExpect(content -> assertThat(content.getResponse().getContentAsString()).doesNotContain("supersecret123"));
    }

    @Test
    void otherUsersDeploymentsAreInvisible() throws Exception {
        ProjectMaster foreign = otherUsersProject();
        Deployment theirs = deploymentRepository.save(
                Deployment.queue(foreign, null, ExecutionMode.SIMULATED, Instant.now()));

        mockMvc.perform(get("/api/deployments/{id}", theirs.getId())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/deployments/{id}/logs", theirs.getId())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/deployments/{id}/history", theirs.getId())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/deployments/{id}/cancel", theirs.getId())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/deployments")).andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(get("/api/dashboard/summary")).andExpect(jsonPath("$.queued").value(0));
    }

    @Test
    void dashboardAndHealthReportState() throws Exception {
        long live = createDeployment(createProject("api", "npm ci"));
        long failed = createDeployment(createProject("broken", "fail"));
        runAll();
        createDeployment(createProject("waiting", "npm ci"));

        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(jsonPath("$.totalProjects").value(3))
                .andExpect(jsonPath("$.queued").value(1))
                .andExpect(jsonPath("$.successful").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.recentDeployments", hasSize(3)));
        assertThat(statusOf(live)).isEqualTo(DeploymentStatus.LIVE);
        assertThat(statusOf(failed)).isEqualTo(DeploymentStatus.FAILED);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.deploymentExecutionMode").value("SIMULATED"));
    }
}
