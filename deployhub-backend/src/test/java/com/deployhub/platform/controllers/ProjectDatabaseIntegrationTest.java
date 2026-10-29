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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    // ---------------------------------------------------------------- import / export

    private long projectWithDatabase() throws Exception {
        long projectId = createProject("shop-api", "npm ci");
        mockMvc.perform(post("/api/projects/{id}/database", projectId)).andExpect(status().isCreated());
        return projectId;
    }

    private static MockMultipartFile sqlFile(String name, String content) {
        return new MockMultipartFile("file", name, "application/sql", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void importingRunsTheCleanedFileAsTheProjectsOwnUser() throws Exception {
        long projectId = projectWithDatabase();
        String name = "p" + projectId + "_shop_api";
        when(server.countTables(anyString(), eq(name))).thenReturn(0, 3);
        String[] seen = new String[1];
        doAnswer(call -> {
            seen[0] = java.nio.file.Files.readString(call.getArgument(4));
            return null;
        }).when(server).importSql(anyString(), eq(name), eq(name), anyString(), any(), eq(false));

        mockMvc.perform(multipart("/api/projects/{id}/database/import", projectId)
                        .file(sqlFile("mydb.sql", "USE `mydb`;\nCREATE TABLE a (id INT);\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tablesAfter").value(3))
                .andExpect(jsonPath("$.message").value(containsString("now has 3 tables")))
                .andExpect(jsonPath("$.message").value(containsString("left out 1 line")));

        verify(server).importSql(anyString(), eq(name), eq(name), eq(env(projectId, "DB_PASSWORD")), any(), eq(false));
        assertThat(seen[0]).contains("CREATE TABLE a").contains("-- removed by DeployHub: USE `mydb`;");
        assertThat(auditEventRepository.findAll()).anyMatch(e -> e.getType() == AuditEventType.DATABASE_IMPORTED);
    }

    @Test
    void aDatabaseWithTablesIsOnlyReplacedWhenAskedTo() throws Exception {
        long projectId = projectWithDatabase();
        when(server.countTables(anyString(), anyString())).thenReturn(4);

        mockMvc.perform(multipart("/api/projects/{id}/database/import", projectId).file(sqlFile("a.sql", "SELECT 1;")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already has 4 tables")));
        verify(server, never()).importSql(anyString(), anyString(), anyString(), anyString(), any(), anyBoolean());

        mockMvc.perform(multipart("/api/projects/{id}/database/import", projectId).file(sqlFile("a.sql", "SELECT 1;"))
                        .param("replace", "true"))
                .andExpect(status().isOk());
        verify(server).importSql(anyString(), anyString(), anyString(), anyString(), any(), eq(true));
    }

    @Test
    void mysqlErrorsAndWrongFilesAreExplained() throws Exception {
        long projectId = projectWithDatabase();
        doThrow(new ManagedDatabaseServer.SqlFailed("ERROR 1064 (42000) at line 1: You have an error in your SQL syntax"))
                .when(server).importSql(anyString(), anyString(), anyString(), anyString(), any(), anyBoolean());

        mockMvc.perform(multipart("/api/projects/{id}/database/import", projectId).file(sqlFile("a.sql", "CREATE TABEL x;")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file").value(containsString("SQL syntax")));
        mockMvc.perform(multipart("/api/projects/{id}/database/import", projectId).file(sqlFile("photo.png", "x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file").value(containsString(".sql")));
        mockMvc.perform(multipart("/api/projects/{id}/database/import", projectId).file(sqlFile("empty.sql", "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportingDownloadsTheDatabaseAsASqlFile() throws Exception {
        long projectId = projectWithDatabase();
        String name = "p" + projectId + "_shop_api";
        doAnswer(call -> {
            java.nio.file.Files.writeString(call.getArgument(3), "CREATE TABLE `a` (id int);\n");
            return null;
        }).when(server).exportSql(eq(name), eq(name), anyString(), any());

        var result = mockMvc.perform(get("/api/projects/{id}/database/export", projectId)).andReturn();
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(name + "-")))
                .andExpect(content().string(containsString("CREATE TABLE `a`")));
        assertThat(auditEventRepository.findAll()).anyMatch(e -> e.getType() == AuditEventType.DATABASE_EXPORTED);
    }

    @Test
    void someoneElseCantImportOrExport() throws Exception {
        long projectId = projectWithDatabase();
        var bob = mvcAs(registerUser("Bob", "bob@example.com"));

        bob.perform(multipart("/api/projects/{id}/database/import", projectId).file(sqlFile("a.sql", "DROP TABLE x;")))
                .andExpect(status().isNotFound());
        bob.perform(get("/api/projects/{id}/database/export", projectId)).andExpect(status().isNotFound());
        verify(server, never()).importSql(anyString(), anyString(), anyString(), anyString(), any(), anyBoolean());
    }
}
