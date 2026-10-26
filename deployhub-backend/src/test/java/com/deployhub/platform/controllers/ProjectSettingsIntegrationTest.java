package com.deployhub.platform.controllers;

import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.repositories.ProjectEnvVarRepository;
import com.deployhub.platform.repositories.ProjectRouteRepository;
import com.deployhub.platform.security.SecretCipher;
import com.deployhub.platform.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/projects/{id}/env and /routes. */
class ProjectSettingsIntegrationTest extends IntegrationTestSupport {

    @Autowired ProjectEnvVarRepository envVars;
    @Autowired ProjectRouteRepository routes;
    @Autowired SecretCipher cipher;

    private void setEnv(long projectId, String name, String value) throws Exception {
        mockMvc.perform(put("/api/projects/{id}/env/{name}", projectId, name)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"" + value + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.value").doesNotExist());
    }

    @Test
    void valuesAreStoredEncryptedAndNeverReturned() throws Exception {
        long projectId = createProject("api", "npm ci");
        setEnv(projectId, "DB_PASSWORD", "hunter2-db-pass");
        setEnv(projectId, "DB_HOST", "host.docker.internal");

        mockMvc.perform(get("/api/projects/{id}/env", projectId))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("DB_HOST"))
                .andExpect(jsonPath("$[1].name").value("DB_PASSWORD"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("hunter2"))));
        var stored = envVars.findByProjectIdAndName(projectId, "DB_PASSWORD").orElseThrow();
        assertThat(stored.getValueEncrypted()).startsWith("v1:").doesNotContain("hunter2");
        assertThat(cipher.decrypt(stored.getValueEncrypted())).isEqualTo("hunter2-db-pass");
        assertThat(auditEventRepository.findAll()).filteredOn(e -> e.getType() == AuditEventType.ENV_VAR_SET)
                .hasSize(2).allMatch(e -> !e.getDetails().contains("hunter2"));
    }

    @Test
    void settingAgainReplacesTheValueAndDeleteRemovesIt() throws Exception {
        long projectId = createProject("api", "npm ci");
        setEnv(projectId, "JWT_SECRET", "first-value");
        setEnv(projectId, "JWT_SECRET", "second-value");

        assertThat(envVars.findByProjectIdOrderByNameAsc(projectId)).hasSize(1);
        assertThat(cipher.decrypt(envVars.findByProjectIdAndName(projectId, "JWT_SECRET").orElseThrow()
                .getValueEncrypted())).isEqualTo("second-value");

        mockMvc.perform(delete("/api/projects/{id}/env/{name}", projectId, "JWT_SECRET"))
                .andExpect(status().isNoContent());
        assertThat(envVars.findByProjectIdOrderByNameAsc(projectId)).isEmpty();
    }

    @Test
    void namesAreCheckedAndPortIsReserved() throws Exception {
        long projectId = createProject("api", "npm ci");

        mockMvc.perform(put("/api/projects/{id}/env/{name}", projectId, "1BAD")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        mockMvc.perform(put("/api/projects/{id}/env/{name}", projectId, "PORT")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"8080\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value(containsString("set by DeployHub")));
    }

    @Test
    void anotherUsersProjectIsNotFound() throws Exception {
        ProjectMaster theirs = otherUsersProject();

        mockMvc.perform(get("/api/projects/{id}/env", theirs.getId())).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/projects/{id}/env/{name}", theirs.getId(), "X")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"x\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/projects/{id}/routes", theirs.getId())).andExpect(status().isNotFound());
    }

    @Test
    void routesAreReplacedAsAWholeAndShowTheTargetName() throws Exception {
        long frontend = createProject("web", "npm ci");
        long backend = createProject("api", "npm ci");

        mockMvc.perform(put("/api/projects/{id}/routes", frontend).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routes\":[{\"pathPrefix\":\"/api/\",\"targetProjectId\":" + backend + "}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pathPrefix").value("/api"))
                .andExpect(jsonPath("$[0].targetProjectName").value("api"));
        mockMvc.perform(get("/api/projects/{id}/routes", frontend)).andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(put("/api/projects/{id}/routes", frontend).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routes\":[]}"))
                .andExpect(status().isOk());
        assertThat(routes.findByProjectIdOrderByPathPrefixAsc(frontend)).isEmpty();
    }

    @Test
    void invalidRoutesAreRejected() throws Exception {
        long frontend = createProject("web", "npm ci");
        long backend = createProject("api", "npm ci");
        ProjectMaster theirs = otherUsersProject();
        String[] bodies = {
                "{\"routes\":[{\"pathPrefix\":\"/\",\"targetProjectId\":" + backend + "}]}",
                "{\"routes\":[{\"pathPrefix\":\"/api;x\",\"targetProjectId\":" + backend + "}]}",
                "{\"routes\":[{\"pathPrefix\":\"/api\",\"targetProjectId\":" + frontend + "}]}",
                "{\"routes\":[{\"pathPrefix\":\"/api\",\"targetProjectId\":" + theirs.getId() + "}]}",
                "{\"routes\":[{\"pathPrefix\":\"/api\",\"targetProjectId\":" + backend + "},"
                        + "{\"pathPrefix\":\"/api/\",\"targetProjectId\":" + backend + "}]}"};
        for (String body : bodies) {
            mockMvc.perform(put("/api/projects/{id}/routes", frontend).contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isBadRequest());
        }
        assertThat(routes.findByProjectIdOrderByPathPrefixAsc(frontend)).isEmpty();
    }

    @Test
    void deletingAProjectDeletesItsVariablesAndTheRoutesPointingToIt() throws Exception {
        long frontend = createProject("web", "npm ci");
        long backend = createProject("api", "npm ci");
        setEnv(backend, "DB_HOST", "db");
        mockMvc.perform(put("/api/projects/{id}/routes", frontend).contentType(MediaType.APPLICATION_JSON)
                .content("{\"routes\":[{\"pathPrefix\":\"/api\",\"targetProjectId\":" + backend + "}]}"));

        mockMvc.perform(delete("/api/projects/{id}", backend)).andExpect(status().isNoContent());

        assertThat(envVars.findByProjectIdOrderByNameAsc(backend)).isEmpty();
        assertThat(routes.findByProjectIdOrderByPathPrefixAsc(frontend)).isEmpty();
    }
}
