package com.deployhub.platform.deployment;

import com.deployhub.platform.models.DTO.DeploymentCreationResult;
import com.deployhub.platform.models.DTO.DeploymentRequestDTO;
import com.deployhub.platform.services.Interface.IDeploymentService;
import com.deployhub.platform.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdempotencyAndAdmissionIntegrationTest extends IntegrationTestSupport {

    @Autowired IDeploymentService deploymentService;

    @Test
    void repeatingARequestWithTheSameKeyReturnsTheOriginalDeployment() throws Exception {
        long projectId = createProject("api", "npm ci");

        long first = idFrom(mockMvc.perform(post("/api/projects/{id}/deployments", projectId)
                        .header("Idempotency-Key", "click-123"))
                .andExpect(status().isAccepted())
                .andReturn());
        long again = idFrom(mockMvc.perform(post("/api/projects/{id}/deployments", projectId)
                        .header("Idempotency-Key", "click-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn());

        assertThat(again).isEqualTo(first);
        assertThat(deploymentRepository.count()).isEqualTo(1);
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() throws Exception {
        long api = createProject("api", "npm ci");
        long web = createProject("web", "npm ci");
        mockMvc.perform(post("/api/projects/{id}/deployments", api).header("Idempotency-Key", "k-1"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/projects/{id}/deployments", web).header("Idempotency-Key", "k-1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Idempotency key reused"));
        mockMvc.perform(post("/api/projects/{id}/deployments", api).header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commitRef\":\"v2.0.0\"}"))
                .andExpect(status().isUnprocessableContent());

        assertThat(deploymentRepository.count()).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerUser() throws Exception {
        MockMvc bob = mvcAs(registerUser("Bob", "bob@example.com"));
        long aliceProject = createProject("api", "npm ci");
        long bobProject = createProject(bob, "api", "npm ci");

        mockMvc.perform(post("/api/projects/{id}/deployments", aliceProject).header("Idempotency-Key", "same"))
                .andExpect(status().isAccepted());
        bob.perform(post("/api/projects/{id}/deployments", bobProject).header("Idempotency-Key", "same"))
                .andExpect(status().isAccepted());

        assertThat(deploymentRepository.count()).isEqualTo(2);
    }

    @Test
    void invalidKeyIsRejected() throws Exception {
        long projectId = createProject("api", "npm ci");
        mockMvc.perform(post("/api/projects/{id}/deployments", projectId).header("Idempotency-Key", "has spaces!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['Idempotency-Key']").exists());
    }

    /** Ten identical requests at the same instant (a double-click storm): exactly one deployment. */
    @Test
    void concurrentDuplicatesCreateExactlyOneDeployment() throws Exception {
        long projectId = createProject("api", "npm ci");
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<DeploymentCreationResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<DeploymentCreationResult> call = () -> {
                actAs(alice);
                try {
                    start.await();
                    return deploymentService.create(projectId, new DeploymentRequestDTO(null), "storm-key");
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
            results.add(pool.submit(call));
        }
        start.countDown();

        List<DeploymentCreationResult> outcomes = new ArrayList<>();
        for (Future<DeploymentCreationResult> f : results) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(outcomes).extracting(r -> r.deployment().id()).containsOnly(outcomes.getFirst().deployment().id());
        assertThat(outcomes).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(deploymentRepository.count()).isEqualTo(1);
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    void admissionControlLimitsDeploymentsInProgressPerUser() throws Exception {
        long projectId = createProject("api", "npm ci");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(createDeployment(projectId));
        }

        mockMvc.perform(post("/api/projects/{id}/deployments", projectId))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("5 deployments queued or running")));

        mockMvc.perform(post("/api/deployments/{id}/cancel", ids.getFirst())).andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/{id}/deployments", projectId)).andExpect(status().isAccepted());

        // Another user is not affected by Alice's queue.
        MockMvc bob = mvcAs(registerUser("Bob", "bob@example.com"));
        createDeployment(bob, createProject(bob, "bob-api", "npm ci"));
    }
}
