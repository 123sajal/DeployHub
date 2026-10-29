package com.deployhub.platform.controllers;

import com.deployhub.platform.repositories.ProjectEnvVarRepository;
import com.deployhub.platform.security.SecretCipher;
import com.deployhub.platform.support.FakeRepositoryFiles;
import com.deployhub.platform.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;


import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "What this app needs": the list from the repository and Fill in automatically, with a fake repository. */
@Import(EnvRequirementsIntegrationTest.FakeRepositoryConfig.class)
class EnvRequirementsIntegrationTest extends IntegrationTestSupport {


    @Autowired ProjectEnvVarRepository envVars;
    @Autowired FakeRepositoryFiles repo;
    @Autowired SecretCipher cipher;

    private long projectId;

    @BeforeEach
    void employeeManagementBackend() throws Exception {
        repo.reset();
        repo.files.put("backend/.env.example", """
                PORT=5000
                DB_HOST=localhost
                DB_USER=root
                DB_USERNAME=root
                # JWT Configuration
                JWT_SECRET=your_super_secret_jwt_key_change_in_production
                JWT_EXPIRES_IN=24h
                CORS_ORIGIN=http://localhost:4200
                SMTP_PASSWORD=
                """);
        projectId = idFrom(mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"ems-api","repositoryUrl":"https://github.com/acme/ems","branch":"main","appType":"NODE_JS",
                 "rootDirectory":"backend","startCommand":"npm start"}"""))
                .andExpect(status().isCreated()).andReturn());
    }

    @Test
    void listsWhatTheAppNeedsAndWhatCanBeFilledInAutomatically() throws Exception {
        mockMvc.perform(get("/api/projects/{id}/env/requirements", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.name=='PORT')].status").value("PROVIDED"))
                .andExpect(jsonPath("$.items[?(@.name=='JWT_SECRET')].kind").value("SECRET"))
                .andExpect(jsonPath("$.items[?(@.name=='JWT_SECRET')].suggestedValue").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.items[?(@.name=='SMTP_PASSWORD')].kind").value("EXTERNAL"))
                .andExpect(jsonPath("$.databaseSuggested").value(true))
                .andExpect(jsonPath("$.autoFillable", hasItems("JWT_SECRET", "JWT_EXPIRES_IN")))
                .andExpect(jsonPath("$.autoFillable", not(hasItems("CORS_ORIGIN"))))
                .andExpect(jsonPath("$.autoFillable", not(hasItems("SMTP_PASSWORD"))));
    }

    @Test
    void fillInGeneratesSecretsUsesExampleValuesAndCopiesDatabaseValues() throws Exception {
        mockMvc.perform(put("/api/projects/{id}/env/DB_USER", projectId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"p7_ems\"}")).andExpect(status().isOk());

        mockMvc.perform(post("/api/projects/{id}/env/requirements/fill", projectId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"names\":[\"JWT_SECRET\",\"JWT_EXPIRES_IN\",\"DB_USERNAME\",\"CORS_ORIGIN\",\"SMTP_PASSWORD\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.name=='JWT_SECRET')].status").value("SET"))
                .andExpect(jsonPath("$.items[?(@.name=='JWT_EXPIRES_IN')].status").value("SET"))
                .andExpect(jsonPath("$.items[?(@.name=='DB_USERNAME')].status").value("SET"))
                .andExpect(jsonPath("$.items[?(@.name=='CORS_ORIGIN')].status").value("MISSING"))
                .andExpect(jsonPath("$.items[?(@.name=='SMTP_PASSWORD')].status").value("MISSING"));

        assertThat(value("JWT_EXPIRES_IN")).isEqualTo("24h");
        assertThat(value("DB_USERNAME")).isEqualTo("p7_ems");
        assertThat(value("JWT_SECRET")).hasSize(44).isNotEqualTo("your_super_secret_jwt_key_change_in_production");
    }

    @Test
    void fillInNeverOverwritesAValueThatIsAlreadySet() throws Exception {
        mockMvc.perform(put("/api/projects/{id}/env/JWT_SECRET", projectId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"my-own-secret\"}")).andExpect(status().isOk());

        mockMvc.perform(post("/api/projects/{id}/env/requirements/fill", projectId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"names\":[\"JWT_SECRET\"]}")).andExpect(status().isOk());

        assertThat(value("JWT_SECRET")).isEqualTo("my-own-secret");
    }

    @Test
    void someoneElsesProjectIsNotFound() throws Exception {
        var bob = mvcAs(registerUser("Bob", "bob@example.com"));
        bob.perform(get("/api/projects/{id}/env/requirements", projectId)).andExpect(status().isNotFound());
        bob.perform(post("/api/projects/{id}/env/requirements/fill", projectId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"names\":[\"JWT_SECRET\"]}")).andExpect(status().isNotFound());
    }

    private String value(String name) {
        return cipher.decrypt(envVars.findByProjectIdAndName(projectId, name).orElseThrow().getValueEncrypted());
    }

    @TestConfiguration
    static class FakeRepositoryConfig {

        @Bean
        @Primary
        FakeRepositoryFiles fakeRepositoryFiles() {
            return new FakeRepositoryFiles();
        }
    }
}
