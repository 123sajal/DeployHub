package com.deployhub.platform.persistence;

import com.deployhub.platform.DeployhubBackendApplication;
import com.deployhub.platform.models.DTO.DeploymentResponseDTO;
import com.deployhub.platform.models.DTO.ProjectRequestDTO;
import com.deployhub.platform.models.entities.UserMaster;
import com.deployhub.platform.models.enums.AccountStatus;
import com.deployhub.platform.models.enums.ApplicationType;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.repositories.UserRepository;
import com.deployhub.platform.security.AuthenticatedUser;
import com.deployhub.platform.services.Interface.IDeploymentService;
import com.deployhub.platform.services.Interface.IProjectService;
import com.deployhub.platform.models.enums.JobStatus;
import com.deployhub.platform.repositories.DeploymentJobRepository;
import com.deployhub.platform.worker.DeploymentJobWorker;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the whole application, writes data, shuts it down completely, starts a brand-new
 * application against the same database and checks the data is still there - including a
 * user account that can still log in. Subclasses choose the database.
 */
abstract class PersistenceAcrossRestartTestBase {

    private static final String PASSWORD = "restart-password-123";

    /** JDBC settings for the database under test; passed as command-line args (highest precedence). */
    protected abstract Map<String, String> databaseProperties();

    private ConfigurableApplicationContext startApplication() {
        // Short lease so the job abandoned by the "crashed" first instance becomes claimable quickly.
        List<String> args = new ArrayList<>(List.of("--spring.profiles.active=test", "--deployhub.worker.lease-duration=1s"));
        databaseProperties().forEach((key, value) -> args.add("--" + key + "=" + value));
        return new SpringApplicationBuilder(DeployhubBackendApplication.class)
                .web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    private static void actAs(UserMaster user) {
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail(), user.getDisplayName(), null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private static void sleepPastLease() {
        try {
            Thread.sleep(1_200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void accountsQueuedJobsAndIdempotencyKeysSurviveACrashAndRestart() {
        String suffix = String.valueOf(System.nanoTime() % 1_000_000);
        String name = "restart-" + suffix;
        String email = "restart-" + suffix + "@example.com";
        long projectId;
        long deploymentId;

        try (ConfigurableApplicationContext first = startApplication()) {
            Instant now = Instant.now();
            UserMaster user = new UserMaster();
            user.setEmail(email);
            user.setDisplayName("Restart Tester");
            user.setPasswordHash(first.getBean(PasswordEncoder.class).encode(PASSWORD));
            user.setStatus(AccountStatus.ACTIVE);
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            first.getBean(UserRepository.class).save(user);
            actAs(user);

            projectId = first.getBean(IProjectService.class).create(new ProjectRequestDTO(name,
                    "https://github.com/acme/" + name, "main", ApplicationType.NODE_JS, null, "npm ci", "node server.js", null, null)).id();
            DeploymentJobWorker worker = first.getBean(DeploymentJobWorker.class);
            worker.runAvailableJobs(1_000); // empty the queue (the database may be shared with other tests)
            deploymentId = first.getBean(IDeploymentService.class).create(projectId, null, "restart-key").deployment().id();
            // A worker claims the job, then the whole application dies before running it.
            assertThat(worker.claimNext()).get().extracting(DeploymentJobWorker.ClaimedJob::deploymentId)
                    .isEqualTo(deploymentId);
            SecurityContextHolder.clearContext();
        }
        sleepPastLease();

        try (ConfigurableApplicationContext second = startApplication()) {
            // The account and its password hash survived: a real login still works.
            Authentication login = second.getBean(AuthenticationManager.class)
                    .authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, PASSWORD));
            AuthenticatedUser principal = (AuthenticatedUser) login.getPrincipal();
            SecurityContextHolder.getContext().setAuthentication(login);

            assertThat(second.getBean(IProjectService.class).get(projectId).name()).isEqualTo(name);
            assertThat(principal.getEmail()).isEqualTo(email);

            assertThat(second.getBean(IDeploymentService.class).get(deploymentId).status()).isEqualTo(DeploymentStatus.QUEUED);
            Flyway flyway = second.getBean(Flyway.class);
            assertThat(flyway.info().pending()).as("no migration re-runs on restart").isEmpty();

            // The queued job survived the crash and is recovered by the new instance's worker.
            // (The database may be shared with other test classes, so check our job, not how many ran.)
            assertThat(second.getBean(DeploymentJobWorker.class).runAvailableJobs(100)).isGreaterThanOrEqualTo(1);
            DeploymentResponseDTO deployment = second.getBean(IDeploymentService.class).get(deploymentId);
            assertThat(deployment.status()).isEqualTo(DeploymentStatus.LIVE);
            var job = second.getBean(DeploymentJobRepository.class).findByDeploymentId(deploymentId).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(job.getAttempt()).as("the attempt lost in the crash counts").isEqualTo(2);
            assertThat(second.getBean(IDeploymentService.class).logs(deploymentId, 0, 100).items()).hasSizeGreaterThanOrEqualTo(5);

            // The idempotency key survived too: retrying the original request doesn't create a second deployment.
            var replay = second.getBean(IDeploymentService.class).create(projectId, null, "restart-key");
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.deployment().id()).isEqualTo(deploymentId);
        }
    }
}
