package com.deployhub.platform.controllers;

import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectApiIntegrationTest extends IntegrationTestSupport {

    @Test
    void createsAndReadsProject() throws Exception {
        long id = createProject("shop-api", "npm ci");

        mockMvc.perform(get("/api/projects/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("shop-api"))
                .andExpect(jsonPath("$.repositoryUrl").value("https://github.com/acme/shop-api"))
                .andExpect(jsonPath("$.appType").value("NODE_JS"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void createReturnsLocationHeader() throws Exception {
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("web-app", "npm run build")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/projects/")));
    }

    @Test
    void rejectsInvalidFieldsWithProblemDetail() throws Exception {
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bad Name!","repositoryUrl":"","branch":"main","appType":"NODE_JS","startCommand":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.repositoryUrl").exists())
                .andExpect(jsonPath("$.errors.startCommand").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://169.254.169.254/latest/meta-data", "https://user:pw@github.com/a/b",
            "http://github.com/a/b", "file:///etc/passwd"})
    void rejectsUnsafeRepositoryUrls(String url) throws Exception {
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ssrf-test","repositoryUrl":"%s","branch":"main","appType":"NODE_JS","startCommand":"x"}
                                """.formatted(url)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.repositoryUrl").exists());
    }

    @Test
    void rejectsMalformedJsonAndUnknownEnum() throws Exception {
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("enum-test", "x").replace("NODE_JS", "COBOL")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateNameIsConflict() throws Exception {
        createProject("same-name", "npm ci");
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("same-name", "npm ci")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("same-name")));
    }

    @Test
    void searchesAndFiltersWithPagination() throws Exception {
        createProject("billing-api", "npm ci");
        createProject("billing-worker", "npm ci");
        createProject("marketing-site", "npm ci");

        mockMvc.perform(get("/api/projects").param("search", "BILLING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/projects").param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/projects").param("appType", "PYTHON"))
                .andExpect(jsonPath("$.items", hasSize(0)));

        mockMvc.perform(get("/api/projects").param("size", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchTreatsWildcardsLiterally() throws Exception {
        createProject("abc-app", "npm ci");
        mockMvc.perform(get("/api/projects").param("search", "%"))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void updatesAndDeletesProject() throws Exception {
        long id = createProject("old-name", "npm ci");

        mockMvc.perform(put("/api/projects/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("new-name", "npm run build")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("new-name"))
                .andExpect(jsonPath("$.buildCommand").value("npm run build"));

        mockMvc.perform(delete("/api/projects/{id}", id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/projects/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void cannotDeleteProjectWithDeploymentInProgress() throws Exception {
        long id = createProject("busy-app", "npm ci");
        createDeployment(id);

        mockMvc.perform(delete("/api/projects/{id}", id)).andExpect(status().isConflict());
    }

    @Test
    void otherUsersProjectsAreInvisible() throws Exception {
        ProjectMaster foreign = otherUsersProject();
        createProject("mine", "npm ci");

        mockMvc.perform(get("/api/projects/{id}", foreign.getId())).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/projects/{id}", foreign.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("hijack", "x")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/projects/{id}", foreign.getId())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/projects/{id}/deployments", foreign.getId())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/projects"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("mine"));
    }

    private static String withFolder(String folder) {
        return """
                {"name":"ems-api","repositoryUrl":"https://github.com/acme/ems","branch":"main","appType":"NODE_JS",
                 "rootDirectory":%s,"buildCommand":"npm ci","startCommand":"npm start"}
                """.formatted(folder == null ? "null" : "\"" + folder + "\"");
    }

    @Test
    void theAppFolderIsStoredTidiedAndEmptyMeansTheRepositoryRoot() throws Exception {
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(withFolder(" ./backend/ ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rootDirectory").value("backend"));

        long id = createProject("root-app", "npm ci");
        mockMvc.perform(get("/api/projects/{id}", id)).andExpect(jsonPath("$.rootDirectory").doesNotExist());
        mockMvc.perform(put("/api/projects/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(withFolder("apps/web").replace("ems-api", "root-app")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootDirectory").value("apps/web"));
        mockMvc.perform(put("/api/projects/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(withFolder("").replace("ems-api", "root-app")))
                .andExpect(jsonPath("$.rootDirectory").doesNotExist());
    }

    @Test
    void theAppFolderMustStayInsideTheRepository() throws Exception {
        for (String bad : new String[] {"../secrets", "/etc", "backend/../../x", "a b", "back;rm", ".git", "~/x"}) {
            mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(withFolder(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.rootDirectory").exists());
        }
    }

    @Test
    void spacesBeforeOrAfterTheNameBranchAndHealthPathDontCount() throws Exception {
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"employeemanagement ","repositoryUrl":" https://github.com/acme/ems ","branch":" main",
                         "appType":"NODE_JS","startCommand":"npm start","healthCheckPath":"/api/health "}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("employeemanagement"))
                .andExpect(jsonPath("$.repositoryUrl").value("https://github.com/acme/ems"))
                .andExpect(jsonPath("$.branch").value("main"))
                .andExpect(jsonPath("$.healthCheckPath").value("/api/health"));
    }

    @Test
    void detectingOnlyLooksAtAllowedPublicRepositoriesAndFolders() throws Exception {
        for (String body : new String[] {
                "{\"repositoryUrl\":\"http://169.254.169.254/latest\",\"branch\":\"main\"}",
                "{\"repositoryUrl\":\"https://github.com/acme/app\",\"branch\":\"main\",\"rootDirectory\":\"../x\"}",
                "{\"repositoryUrl\":\"https://github.com/acme/app\",\"branch\":\"--upload-pack=x;..\"}"}) {
            mockMvc.perform(post("/api/projects/detect").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        anonymousMvc.perform(post("/api/projects/detect").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryUrl\":\"https://github.com/acme/app\",\"branch\":\"main\"}"))
                .andExpect(status().isUnauthorized());
    }
}
