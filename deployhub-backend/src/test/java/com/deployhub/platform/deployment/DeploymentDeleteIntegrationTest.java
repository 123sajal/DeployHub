package com.deployhub.platform.deployment;

import com.deployhub.platform.models.entities.Deployment;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.support.IntegrationTestSupport;
import com.deployhub.platform.worker.DeploymentJobWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DELETE /api/deployments/{id}: only finished deployments that aren't the running app. */
class DeploymentDeleteIntegrationTest extends IntegrationTestSupport {

    @Autowired DeploymentJobWorker worker;

    @Test
    void aFinishedDeploymentIsDeletedWithItsLogsHistoryJobAndIdempotencyKey() throws Exception {
        long projectId = createProject("broken", "npm run fail");
        long id = idFrom(mockMvc.perform(post("/api/projects/{id}/deployments", projectId)
                .header("Idempotency-Key", "del-1")).andReturn());
        worker.runAvailableJobs(10);
        mockMvc.perform(get("/api/deployments/{id}", id))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.deletable").value(true));

        mockMvc.perform(delete("/api/deployments/{id}", id)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/deployments/{id}", id)).andExpect(status().isNotFound());
        assertThat(deploymentLogRepository.findAll()).noneMatch(l -> l.getDeploymentId() == id);
        assertThat(statusHistoryRepository.findByDeploymentIdOrderByIdAsc(id)).isEmpty();
        assertThat(jobRepository.findByDeploymentId(id)).isEmpty();
        assertThat(idempotencyRecordRepository.count()).isZero();
        assertThat(auditEventRepository.findAll()).anyMatch(e -> e.getType() == AuditEventType.DEPLOYMENT_DELETED);
        // The key is free again: the same request now creates a new deployment instead of replaying the old one.
        mockMvc.perform(post("/api/projects/{id}/deployments", projectId).header("Idempotency-Key", "del-1"))
                .andExpect(status().isAccepted());
    }

    @Test
    void theLiveDeploymentAndOneInProgressCannotBeDeleted() throws Exception {
        long projectId = createProject("api", "npm ci");
        long live = createDeployment(projectId);
        worker.runAvailableJobs(10);
        long queued = createDeployment(projectId);

        mockMvc.perform(get("/api/deployments/{id}", live)).andExpect(jsonPath("$.deletable").value(false));
        mockMvc.perform(delete("/api/deployments/{id}", live))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Deployment #" + live
                        + " is live. Deploy a newer version first (or delete the project)."));
        mockMvc.perform(delete("/api/deployments/{id}", queued))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Deployment #" + queued + " is still in progress. Cancel it first."));
        assertThat(deploymentRepository.count()).isEqualTo(2);
    }

    @Test
    void deletingTheSourceOfARollbackKeepsTheRollback() throws Exception {
        long projectId = createProject("api", "npm ci");
        long first = createDeployment(projectId);
        worker.runAvailableJobs(10);
        createDeployment(projectId);
        worker.runAvailableJobs(10);
        long rollback = idFrom(mockMvc.perform(post("/api/deployments/{id}/rollback", first)).andReturn());
        worker.runAvailableJobs(10);
        assertThat(deploymentRepository.findById(first).orElseThrow().getStatus())
                .isEqualTo(DeploymentStatus.SUPERSEDED);

        mockMvc.perform(delete("/api/deployments/{id}", first)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/deployments/{id}", rollback))
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.rollbackOfId").doesNotExist());
    }

    @Test
    void otherUsersDeploymentsCannotBeDeleted() throws Exception {
        Deployment theirs = deploymentRepository.save(
                Deployment.queue(otherUsersProject(), null, ExecutionMode.SIMULATED, Instant.now()));

        mockMvc.perform(delete("/api/deployments/{id}", theirs.getId())).andExpect(status().isNotFound());
        assertThat(deploymentRepository.existsById(theirs.getId())).isTrue();
    }
}
