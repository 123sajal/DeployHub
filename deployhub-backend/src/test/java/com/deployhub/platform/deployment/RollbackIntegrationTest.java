package com.deployhub.platform.deployment;

import com.deployhub.platform.models.entities.Deployment;
import com.deployhub.platform.models.entities.DeploymentStatusChange;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.support.IntegrationTestSupport;
import com.deployhub.platform.worker.DeploymentJobWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RollbackIntegrationTest extends IntegrationTestSupport {

    @Autowired DeploymentJobWorker worker;

    private long deployCommit(long projectId, String commitRef) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects/{id}/deployments", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commitRef\":\"" + commitRef + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        long id = idFrom(result);
        worker.runAvailableJobs(10);
        return id;
    }

    private DeploymentStatus statusOf(long id) {
        return deploymentRepository.findById(id).orElseThrow().getStatus();
    }

    @Test
    void rollbackRedeploysTheEarlierCommitAsANewDeploymentThatGoesLive() throws Exception {
        long projectId = createProject("api", "npm ci");
        long v1 = deployCommit(projectId, "v1.0.0");
        long v2 = deployCommit(projectId, "v2.0.0");
        assertThat(statusOf(v1)).isEqualTo(DeploymentStatus.SUPERSEDED);

        mockMvc.perform(get("/api/deployments/{id}", v1)).andExpect(jsonPath("$.rollbackable").value(true));
        mockMvc.perform(get("/api/deployments/{id}", v2)).andExpect(jsonPath("$.rollbackable").value(false));

        long rollback = idFrom(mockMvc.perform(post("/api/deployments/{id}/rollback", v1))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", startsWith("/api/deployments/")))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.rollbackOfId").value(v1))
                .andExpect(jsonPath("$.commitRef").value("v1.0.0"))
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andReturn());
        assertThat(rollback).isGreaterThan(v2);

        worker.runAvailableJobs(10);

        assertThat(statusOf(rollback)).isEqualTo(DeploymentStatus.LIVE);
        assertThat(statusOf(v2)).isEqualTo(DeploymentStatus.SUPERSEDED);
        assertThat(statusOf(v1)).as("the source stays as it was").isEqualTo(DeploymentStatus.SUPERSEDED);
        assertThat(statusHistoryRepository.findByDeploymentIdOrderByIdAsc(rollback).getFirst())
                .extracting(DeploymentStatusChange::getReason)
                .isEqualTo("Rollback to deployment #" + v1 + " requested");
        assertThat(auditEventRepository.findAll())
                .anyMatch(e -> e.getType() == AuditEventType.DEPLOYMENT_ROLLBACK_REQUESTED);
        mockMvc.perform(get("/api/deployments/{id}/logs", rollback))
                .andExpect(jsonPath("$.items[0].message").value(containsString("redeploying deployment #" + v1)));
    }

    @Test
    void onlyAReplacedDeploymentCanBeRolledBackTo() throws Exception {
        long projectId = createProject("api", "npm ci");
        long live = deployCommit(projectId, "v1");
        long failed = deployCommit(createProject("broken", "fail"), "v1");
        long queued = createDeployment(projectId);

        mockMvc.perform(post("/api/deployments/{id}/rollback", live))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Deployment #" + live + " is already live"));
        mockMvc.perform(post("/api/deployments/{id}/rollback", failed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("this one is FAILED")));
        mockMvc.perform(post("/api/deployments/{id}/rollback", queued))
                .andExpect(status().isConflict());
        assertThat(deploymentRepository.count()).as("nothing was created").isEqualTo(3);
    }

    @Test
    void rollbackIsIdempotentAndItsKeyCannotBeReusedForADeploy() throws Exception {
        long projectId = createProject("api", "npm ci");
        long v1 = deployCommit(projectId, "v1");
        deployCommit(projectId, "v2");

        long first = idFrom(mockMvc.perform(post("/api/deployments/{id}/rollback", v1).header("Idempotency-Key", "rb-1"))
                .andExpect(status().isAccepted()).andReturn());
        mockMvc.perform(post("/api/deployments/{id}/rollback", v1).header("Idempotency-Key", "rb-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(first));
        mockMvc.perform(post("/api/projects/{id}/deployments", projectId).header("Idempotency-Key", "rb-1"))
                .andExpect(status().isUnprocessableContent());
        assertThat(deploymentRepository.count()).isEqualTo(3);
    }

    @Test
    void rollbackCountsTowardsTheActiveDeploymentLimit() throws Exception {
        long projectId = createProject("api", "npm ci");
        long v1 = deployCommit(projectId, "v1");
        deployCommit(projectId, "v2");
        for (int i = 0; i < 5; i++) {
            createDeployment(projectId);
        }

        mockMvc.perform(post("/api/deployments/{id}/rollback", v1)).andExpect(status().isTooManyRequests());
    }

    @Test
    void otherUsersDeploymentsCannotBeRolledBackTo() throws Exception {
        Deployment theirs = deploymentRepository.save(
                Deployment.queue(otherUsersProject(), "v1", ExecutionMode.SIMULATED, Instant.now()));

        mockMvc.perform(post("/api/deployments/{id}/rollback", theirs.getId())).andExpect(status().isNotFound());
    }

    @Test
    void projectWithRollbacksCanStillBeDeleted() throws Exception {
        long projectId = createProject("api", "npm ci");
        long v1 = deployCommit(projectId, "v1");
        deployCommit(projectId, "v2");
        mockMvc.perform(post("/api/deployments/{id}/rollback", v1)).andExpect(status().isAccepted());
        worker.runAvailableJobs(10);

        mockMvc.perform(delete("/api/projects/{id}", projectId)).andExpect(status().isNoContent());
        assertThat(deploymentRepository.count()).isZero();
    }
}
