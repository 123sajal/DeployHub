package com.deployhub.platform.auth;

import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.support.IntegrationTestSupport;
import com.deployhub.platform.worker.DeploymentJobWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two real accounts, Alice and Bob, each with their own session. Alice tries every endpoint
 * against Bob's ids. Each attempt must look exactly like "does not exist" and change nothing.
 */
class TenantIsolationIntegrationTest extends IntegrationTestSupport {

    @Autowired DeploymentJobWorker worker;

    private MockMvc bobMvc;
    private long bobProject;
    private long bobDeployment;

    @BeforeEach
    void bobCreatesData() throws Exception {
        bobMvc = mvcAs(registerUser("Bob", "bob@example.com"));
        bobProject = createProject(bobMvc, "bobs-secret-api", "npm ci");
        bobDeployment = createDeployment(bobMvc, bobProject);
        worker.execute(worker.claimNext().orElseThrow()); // so Bob's deployment has logs (it goes LIVE)
    }

    @Test
    void aliceCannotReadBobsProjectOrDeployment() throws Exception {
        String notFoundBody = mockMvc.perform(get("/api/projects/{id}", 999_999))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        String bobsBody = mockMvc.perform(get("/api/projects/{id}", bobProject))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        // Same shape as a truly missing id (only the id in the message differs): no hint that it exists.
        assertThat(bobsBody.replace(String.valueOf(bobProject), "ID"))
                .isEqualTo(notFoundBody.replace("999999", "ID"));

        mockMvc.perform(get("/api/deployments/{id}", bobDeployment)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/deployments/{id}/logs", bobDeployment)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/deployments/{id}/history", bobDeployment)).andExpect(status().isNotFound());
    }

    @Test
    void aliceCannotChangeBobsResources() throws Exception {
        mockMvc.perform(put("/api/projects/{id}", bobProject).contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("hijacked", "rm -rf /")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/projects/{id}", bobProject)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/projects/{id}/deployments", bobProject)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/deployments/{id}/cancel", bobDeployment)).andExpect(status().isNotFound());

        bobMvc.perform(get("/api/projects/{id}", bobProject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("bobs-secret-api"))
                .andExpect(jsonPath("$.buildCommand").value("npm ci"));
        assertThat(deploymentRepository.findById(bobDeployment).orElseThrow().getStatus())
                .isEqualTo(DeploymentStatus.LIVE);
        assertThat(deploymentRepository.count()).as("Alice could not queue a deployment on Bob's project").isEqualTo(1);
    }

    @Test
    void listsSearchesAndFiltersOnlyShowOwnData() throws Exception {
        createProject("alice-site", "npm ci");

        mockMvc.perform(get("/api/projects")).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("alice-site"));
        mockMvc.perform(get("/api/projects").param("search", "bobs-secret"))
                .andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/projects").param("appType", "NODE_JS")).andExpect(jsonPath("$.items", hasSize(1)));
        mockMvc.perform(get("/api/deployments")).andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(get("/api/deployments").param("projectId", String.valueOf(bobProject)))
                .andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(get("/api/deployments").param("status", "LIVE")).andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(jsonPath("$.totalProjects").value(1))
                .andExpect(jsonPath("$.running").value(0))
                .andExpect(jsonPath("$.recentDeployments", hasSize(0)));

        bobMvc.perform(get("/api/projects")).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("bobs-secret-api"));
    }

    @Test
    void projectNamesAreUniquePerUserNotGlobally() throws Exception {
        createProject("bobs-secret-api", "npm ci"); // Alice may reuse Bob's project name
        assertThat(projectRepository.count()).isEqualTo(2);
    }

    /** The background worker has no user session; it must keep each deployment within its own project. */
    @Test
    void workerKeepsEachOwnersDeploymentsSeparate() throws Exception {
        long aliceProject = createProject("bobs-secret-api", "npm ci"); // same name, different owner
        long aliceDeployment = createDeployment(aliceProject);
        worker.runAvailableJobs(100);
        assertThat(deploymentRepository.findById(aliceDeployment).orElseThrow().getStatus()).isEqualTo(DeploymentStatus.LIVE);
        assertThat(deploymentRepository.findById(bobDeployment).orElseThrow().getStatus()).isEqualTo(DeploymentStatus.LIVE);

        long bobSecond = createDeployment(bobMvc, bobProject);
        worker.runAvailableJobs(100);

        assertThat(deploymentRepository.findById(bobSecond).orElseThrow().getStatus()).isEqualTo(DeploymentStatus.LIVE);
        assertThat(deploymentRepository.findById(bobDeployment).orElseThrow().getStatus()).isEqualTo(DeploymentStatus.SUPERSEDED);
        assertThat(deploymentRepository.findById(aliceDeployment).orElseThrow().getStatus())
                .as("Bob's new release must not supersede Alice's").isEqualTo(DeploymentStatus.LIVE);

        mockMvc.perform(get("/api/deployments/{id}/logs", aliceDeployment))
                .andExpect(jsonPath("$.items[*].message").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("#" + bobSecond)))));
    }

    @Test
    void auditTrailRecordsTheActingUser() {
        var bobId = userRepository.findByEmail("bob@example.com").orElseThrow().getId();
        assertThat(auditEventRepository.findByUserIdOrderByIdAsc(bobId)).extracting(e -> e.getType())
                .containsSubsequence(AuditEventType.REGISTERED, AuditEventType.PROJECT_CREATED,
                        AuditEventType.DEPLOYMENT_CREATED);
        assertThat(auditEventRepository.findByUserIdOrderByIdAsc(alice.id())).extracting(e -> e.getType())
                .doesNotContain(AuditEventType.PROJECT_CREATED);
    }
}
