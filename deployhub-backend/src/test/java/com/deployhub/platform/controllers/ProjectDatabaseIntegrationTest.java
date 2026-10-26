package com.deployhub.platform.controllers;

import com.deployhub.platform.execution.ManagedDatabaseServer;
import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.repositories.DatabaseServerRepository;
import com.deployhub.platform.repositories.ProjectDatabaseRepository;
import com.deployhub.platform.repositories.ProjectEnvVarRepository;
import com.deployhub.platform.security.SecretCipher;
import com.deployhub.platform.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/projects/{id}/database with the MySQL server mocked: what DeployHub stores, returns and asks the server to do. */
class ProjectDatabaseIntegrationTest extends IntegrationTestSupport {

    @MockitoBean ManagedDatabaseServer server;
    @Autowired ProjectDatabaseRepository databases;
    @Autowired DatabaseServerRepository servers;
    @Autowired ProjectEnvVarRepository envVars;
    @Autowired SecretCipher cipher;

    @BeforeEach
    void endpoints() {
        reset(server);
        when(server.endpoints()).thenReturn(new ManagedDatabaseServer.Endpoints("deployhub-mysql", 3306, "127.0.0.1", 3307));
    }

    private String env(long projectId, String name) {
        return cipher.decrypt(envVars.findByProjectIdAndName(projectId, name).orElseThrow().getValueEncrypted());
    }

    @Test
    void addingADatabaseCreatesItAndSetsTheConnectionVariables() throws Exception {
        long projectId = createProject("shop-api", "npm ci");

        String body = mockMvc.perform(post("/api/projects/{id}/database", projectId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.engine").value("MySQL"))
                .andExpect(jsonPath("$.databaseName").value("p" + projectId + "_shop_api"))
                .andExpect(jsonPath("$.appHost").value("deployhub-mysql"))
                .andExpect(jsonPath("$.localPort").value(3307))
                .andExpect(jsonPath("$.environmentVariables", hasSize(6)))
                .andReturn().getResponse().getContentAsString();
        String password = body.replaceAll(".*\"password\":\"([A-Za-z0-9]+)\".*", "$1");
        assertThat(password).hasSize(24);

        String name = "p" + projectId + "_shop_api";
        verify(server).createDatabase(anyString(), eq(name), eq(name), eq(password));
        assertThat(env(projectId, "DB_HOST")).isEqualTo("deployhub-mysql");
        assertThat(env(projectId, "DB_PORT")).isEqualTo("3306");
        assertThat(env(projectId, "DB_NAME")).isEqualTo(name);
        assertThat(env(projectId, "DB_USER")).isEqualTo(name);
        assertThat(env(projectId, "DB_PASSWORD")).isEqualTo(password);
        assertThat(env(projectId, "DATABASE_URL"))
                .isEqualTo("mysql://" + name + ":" + password + "@deployhub-mysql:3306/" + name);
        assertThat(auditEventRepository.findAll()).anyMatch(e -> e.getType() == AuditEventType.DATABASE_CREATED
                && !e.getDetails().contains(password));

        mockMvc.perform(get("/api/projects/{id}/database", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.databaseName").value(name))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(password))));
    }

    @Test
    void aProjectHasAtMostOneDatabaseAndTheServerPasswordIsCreatedOnce() throws Exception {
        long first = createProject("api", "npm ci");
        long second = createProject("web", "npm ci");
        mockMvc.perform(post("/api/projects/{id}/database", first)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/projects/{id}/database", second)).andExpect(status().isCreated());

        mockMvc.perform(post("/api/projects/{id}/database", first))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Project api already has a database"));
        assertThat(servers.count()).isEqualTo(1);
    }

    @Test
    void noDatabaseIs204() throws Exception {
        long projectId = createProject("api", "npm ci");
        mockMvc.perform(get("/api/projects/{id}/database", projectId)).andExpect(status().isNoContent());
    }

    @Test
    void resettingThePasswordUpdatesTheServerAndTheVariables() throws Exception {
        long projectId = createProject("api", "npm ci");
        mockMvc.perform(post("/api/projects/{id}/database", projectId));
        String old = env(projectId, "DB_PASSWORD");

        String body = mockMvc.perform(post("/api/projects/{id}/database/reset-password", projectId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String fresh = body.replaceAll(".*\"password\":\"([A-Za-z0-9]+)\".*", "$1");

        assertThat(fresh).isNotEqualTo(old).hasSize(24);
        verify(server).changePassword(anyString(), eq("p" + projectId + "_api"), eq(fresh));
        assertThat(env(projectId, "DB_PASSWORD")).isEqualTo(fresh);
        assertThat(env(projectId, "DATABASE_URL")).contains(":" + fresh + "@");
    }

    @Test
    void removingDeletesTheDatabaseAndItsVariablesButNotOtherVariables() throws Exception {
        long projectId = createProject("api", "npm ci");
        mockMvc.perform(post("/api/projects/{id}/database", projectId));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/projects/{id}/env/{name}", projectId, "JWT_SECRET")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"value\":\"keep-me\"}"));

        mockMvc.perform(delete("/api/projects/{id}/database", projectId)).andExpect(status().isNoContent());

        verify(server).dropDatabase(anyString(), eq("p" + projectId + "_api"), eq("p" + projectId + "_api"));
        assertThat(databases.findByProjectId(projectId)).isEmpty();
        assertThat(envVars.findByProjectIdOrderByNameAsc(projectId)).extracting(v -> v.getName())
                .containsExactly("JWT_SECRET");
    }

    @Test
    void deletingTheProjectDeletesItsDatabaseToo() throws Exception {
        long projectId = createProject("api", "npm ci");
        mockMvc.perform(post("/api/projects/{id}/database", projectId));

        mockMvc.perform(delete("/api/projects/{id}", projectId)).andExpect(status().isNoContent());

        verify(server).dropDatabase(anyString(), eq("p" + projectId + "_api"), eq("p" + projectId + "_api"));
        assertThat(databases.findByProjectId(projectId)).isEmpty();
    }

    @Test
    void dockerNotRunningIs503AndNothingIsSaved() throws Exception {
        long projectId = createProject("api", "npm ci");
        doThrow(new IllegalStateException("Starting the MySQL server failed: Docker is not running."))
                .when(server).ensureRunning(anyString());

        mockMvc.perform(post("/api/projects/{id}/database", projectId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(containsString("Docker is not running")));

        verify(server, never()).createDatabase(anyString(), anyString(), anyString(), anyString());
        assertThat(databases.findByProjectId(projectId)).isEmpty();
        assertThat(envVars.findByProjectIdOrderByNameAsc(projectId)).isEmpty();
    }

    @Test
    void anotherUsersProjectIsNotFound() throws Exception {
        ProjectMaster theirs = otherUsersProject();

        mockMvc.perform(post("/api/projects/{id}/database", theirs.getId())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/projects/{id}/database", theirs.getId())).andExpect(status().isNotFound());
        verify(server, never()).createDatabase(anyString(), anyString(), anyString(), anyString());
    }
}
