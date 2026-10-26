package com.deployhub.platform.worker;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.deployment.DeploymentLogWriter;
import com.deployhub.platform.deployment.DeploymentMetrics;
import com.deployhub.platform.deployment.ProjectGateways;
import com.deployhub.platform.execution.DeploymentExecutors;
import com.deployhub.platform.repositories.ProjectEnvVarRepository;
import com.deployhub.platform.security.SecretCipher;
import com.deployhub.platform.deployment.DeploymentTransitionService;
import com.deployhub.platform.models.entities.DeploymentJob;
import com.deployhub.platform.models.entities.DeploymentStatusChange;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.JobStatus;
import com.deployhub.platform.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Several independent workers against one queue. The workers are separate objects with their own
 * ids, exactly as if they were separate processes: they share nothing but the database.
 */
class JobQueueConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired DeploymentTransitionService transitions;
    @Autowired DeploymentLogWriter logWriter;
    @Autowired DeploymentMetrics metrics;
    @Autowired DeploymentExecutors executors;
    @Autowired ProjectGateways gateways;
    @Autowired ProjectEnvVarRepository envVars;
    @Autowired SecretCipher cipher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DeployHubProperties properties;

    private DeploymentJobWorker newWorker(String id, Duration lease) {
        DeployHubProperties.Worker base = properties.worker();
        DeployHubProperties.Worker settings = new DeployHubProperties.Worker(false, id, base.pollInterval(), 1, lease,
                Duration.ZERO, base.maxAttempts(), Duration.ZERO, base.retryMaxDelay());
        return new DeploymentJobWorker(jobRepository, deploymentRepository, projectRepository, transitions, logWriter,
                metrics, executors, gateways, envVars, cipher, transactionManager,
                new DeployHubProperties(settings, properties.deployments(), properties.security(), properties.logs(),
                        properties.metrics(), properties.execution(), properties.gateway(), properties.secrets(),
                        properties.databases()),
                Clock.systemUTC());
    }

    private DeploymentJob jobOf(long deploymentId) {
        return jobRepository.findByDeploymentId(deploymentId).orElseThrow();
    }

    @Test
    void competingWorkersNeverClaimTheSameJob() throws Exception {
        int deploymentsToCreate = 20;
        for (int i = 0; i < deploymentsToCreate; i++) {
            // Spread over 5 users (the per-user admission limit is 5).
            if (i % 5 == 0 && i > 0) {
                mockMvc = mvcAs(registerUser("User " + i, "user" + i + "@example.com"));
            }
            createDeployment(createProject("app-" + i, "npm ci"));
        }

        List<Long> claimed = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            DeploymentJobWorker worker = newWorker("racer-" + w, Duration.ofMinutes(5));
            futures.add(pool.submit(() -> {
                start.await();
                Optional<DeploymentJobWorker.ClaimedJob> job;
                while ((job = worker.claimNext()).isPresent()) {
                    claimed.add(job.get().jobId());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(claimed).as("every job claimed exactly once").hasSize(deploymentsToCreate).doesNotHaveDuplicates();
        assertThat(jobRepository.findAll()).allSatisfy(job -> {
            assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
            assertThat(job.getAttempt()).isEqualTo(1);
            assertThat(job.getLockedBy()).startsWith("racer-");
        });
    }

    @Test
    void jobOfACrashedWorkerIsRecoveredAfterItsLeaseExpires() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        DeploymentJobWorker crashing = newWorker("crashes", Duration.ofMillis(300));
        DeploymentJobWorker healthy = newWorker("healthy", Duration.ofMinutes(5));

        assertThat(crashing.claimNext()).isPresent(); // ...and then the process "dies" without running it
        assertThat(healthy.claimNext()).as("lease still valid: nobody may steal the job").isEmpty();

        Thread.sleep(400); // lease expires
        assertThat(healthy.runAvailableJobs(10)).isEqualTo(1);

        DeploymentJob job = jobOf(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getAttempt()).as("the crashed attempt counts").isEqualTo(2);
        assertThat(deploymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(DeploymentStatus.LIVE);
        assertThat(statusHistoryRepository.findByDeploymentIdOrderByIdAsc(id))
                .filteredOn(c -> c.getToStatus() == DeploymentStatus.BUILDING)
                .extracting(DeploymentStatusChange::getActor).containsExactly("worker:healthy");
    }

    /** A worker that was presumed dead (lease expired) wakes up later: it must not touch the job again. */
    @Test
    void workerThatLostItsLeaseCannotInterfere() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        DeploymentJobWorker slow = newWorker("slow", Duration.ofMillis(300));
        DeploymentJobWorker other = newWorker("other", Duration.ofMinutes(5));

        DeploymentJobWorker.ClaimedJob stale = slow.claimNext().orElseThrow();
        Thread.sleep(400);
        other.runAvailableJobs(10); // takes over and finishes

        slow.execute(stale); // the slow worker finally gets going

        assertThat(jobOf(id).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(jobOf(id).getLockedBy()).isNull();
        assertThat(statusHistoryRepository.findByDeploymentIdOrderByIdAsc(id))
                .as("no step was applied twice")
                .extracting(DeploymentStatusChange::getToStatus)
                .containsExactly(DeploymentStatus.QUEUED, DeploymentStatus.BUILDING, DeploymentStatus.STARTING,
                        DeploymentStatus.LIVE);
    }

    @Test
    void jobThatKeepsKillingWorkersEndsUpDead() throws Exception {
        long id = createDeployment(createProject("api", "npm ci"));
        for (int crash = 1; crash <= 3; crash++) {
            assertThat(newWorker("doomed-" + crash, Duration.ofMillis(100)).claimNext()).isPresent();
            Thread.sleep(150);
        }

        newWorker("survivor", Duration.ofMinutes(5)).runAvailableJobs(10);

        DeploymentJob job = jobOf(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD);
        assertThat(deploymentRepository.findById(id).orElseThrow().getFailureReason())
                .isEqualTo("Gave up after 3 attempts: Worker stopped responding during the last attempt");
    }
}
