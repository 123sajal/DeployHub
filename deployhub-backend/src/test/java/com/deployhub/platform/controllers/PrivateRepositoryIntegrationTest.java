package com.deployhub.platform.controllers;

import com.deployhub.platform.models.enums.AuditEventType;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Private repositories: the access token is checked, stored encrypted, never returned, and used for every read. */
@Import(PrivateRepositoryIntegrationTest.FakeRepositoryConfig.class)
class PrivateRepositoryIntegrationTest extends IntegrationTestSupport {

    private static final String GOOD = "github_pat_11ABCDEFG0123456789_secretpart";

    @Autowired FakeRepositoryFiles repo;
    @Autowired SecretCipher cipher;

    @BeforeEach
    void privateRepository() {
        repo.reset();
        repo.acceptedToken = GOOD;
    }

    private static String project(String name, String url, String token) {
        return """
                {"name":"%s","repositoryUrl":"%s","branch":"main","appType":"NODE_JS","startCommand":"npm start"%s}
                """.formatted(name, url, token == null ? "" : ",\"repositoryToken\":\"" + token + "\"");
    }

    private long create(String token) throws Exception {
        return idFrom(mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content(project("secret-app", "https://github.com/acme/secret-app", token)))
                .andExpect(status().isCreated()).andReturn());
    }

    @Test
    void aWorkingTokenIsSavedEncryptedAndNeverShown() throws Exception {
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content(project("secret-app", "https://github.com/acme/secret-app", GOOD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.privateRepository").value(true))
                .andExpect(jsonPath("$.repositoryTokenUpdatedAt").isNotEmpty())
                .andExpect(content().string(not(containsString(GOOD))));

        var stored = projectRepository.findAll().getFirst();
        assertThat(stored.getRepoTokenEncrypted()).isNotEqualTo(GOOD).doesNotContain("secretpart");
        assertThat(cipher.decrypt(stored.getRepoTokenEncrypted())).isEqualTo(GOOD);
        assertThat(repo.lastToken).as("checked against the repository first").isEqualTo(GOOD);
        assertThat(auditEventRepository.findAll()).anyMatch(e -> e.getType() == AuditEventType.REPO_TOKEN_SET
                && !String.valueOf(e.getDetails()).contains(GOOD));
    }

    @Test
    void aTokenThatDoesntWorkIsRejectedOnTheForm() throws Exception {
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content(project("secret-app", "https://github.com/acme/secret-app", "github_pat_wrong")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.repositoryToken").value(containsString("doesn't work")));
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content(project("secret-app", "https://github.com/acme/secret-app", "bad token; rm -rf")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.repositoryToken").exists());
        assertThat(projectRepository.findAll()).isEmpty();
    }

    @Test
    void editingKeepsTheTokenUnlessTheRepositoryChangesAndItCanBeRemoved() throws Exception {
        long id = create(GOOD);

        mockMvc.perform(put("/api/projects/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(project("secret-app", "https://github.com/acme/secret-app", null)))
                .andExpect(jsonPath("$.privateRepository").value(true));

        mockMvc.perform(put("/api/projects/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(project("secret-app", "https://github.com/acme/other-repo", null)))
                .andExpect(jsonPath("$.privateRepository").value(false)); // never sent to another repository

        mockMvc.perform(put("/api/projects/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(project("secret-app", "https://github.com/acme/other-repo", GOOD)))
                .andExpect(jsonPath("$.privateRepository").value(true));
        mockMvc.perform(delete("/api/projects/{id}/repository-token", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.privateRepository").value(false));
        assertThat(projectRepository.findById(id).orElseThrow().getRepoTokenEncrypted()).isNull();
    }

    @Test
    void theSavedTokenIsUsedToDetectAndToReadWhatTheAppNeeds() throws Exception {
        long id = create(GOOD);
        repo.files.put("package.json", "{\"scripts\":{\"start\":\"node a.js\"}}");
        repo.lastToken = null;

        mockMvc.perform(post("/api/projects/detect").contentType(MediaType.APPLICATION_JSON).content("""
                        {"repositoryUrl":"https://github.com/acme/secret-app","branch":"main","projectId":%d}""".formatted(id)))
                .andExpect(status().isOk());
        assertThat(repo.lastToken).isEqualTo(GOOD);

        repo.lastToken = null;
        mockMvc.perform(get("/api/projects/{id}/env/requirements", id)).andExpect(status().isOk());
        assertThat(repo.lastToken).isEqualTo(GOOD);
    }

    @Test
    void someoneElseCantUseOrRemoveTheToken() throws Exception {
        long id = create(GOOD);
        var bob = mvcAs(registerUser("Bob", "bob@example.com"));
        repo.lastToken = null;

        bob.perform(delete("/api/projects/{id}/repository-token", id)).andExpect(status().isNotFound());
        bob.perform(post("/api/projects/detect").contentType(MediaType.APPLICATION_JSON).content("""
                {"repositoryUrl":"https://github.com/acme/secret-app","branch":"main","projectId":%d}""".formatted(id)))
                .andExpect(status().isOk());
        assertThat(repo.lastToken).as("Bob's detection gets no token").isNull();
    }

    /** Review #6: a token is never tried for someone else's project, and token checks are limited. */
    @Test
    void tokensAreNotCheckedForOthersProjectsAndChecksAreLimited() throws Exception {
        long id = create(GOOD);
        var bob = mvcAs(registerUser("Bob", "bob@example.com"));
        repo.lastToken = null;

        bob.perform(put("/api/projects/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(project("secret-app", "https://github.com/acme/secret-app", "github_pat_guess")))
                .andExpect(status().isNotFound());
        assertThat(repo.lastToken).as("nothing sent to the Git host").isNull();

        repo.acceptedToken = "never-this";
        int tooMany = 0;
        for (int i = 0; i < 25; i++) {
            int code = bob.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                    .content(project("guess-" + i, "https://github.com/acme/secret-app", "github_pat_guess" + i)))
                    .andReturn().getResponse().getStatus();
            if (code == 429) {
                tooMany++;
            }
        }
        assertThat(tooMany).as("after 20 checks a minute").isEqualTo(5);
    }

    /** Review #8: dropping the token because the repository changed is recorded. */
    @Test
    void changingTheRepositoryRecordsThatTheTokenWasRemoved() throws Exception {
        long id = create(GOOD);
        mockMvc.perform(put("/api/projects/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(project("secret-app", "https://github.com/acme/renamed", null))).andExpect(status().isOk());

        assertThat(auditEventRepository.findAll()).anyMatch(e -> e.getType() == AuditEventType.REPO_TOKEN_REMOVED
                && String.valueOf(e.getDetails()).contains("repository URL changed"));
    }

    /** Review #5: a saved token that can't be decrypted is explained instead of breaking the page. */
    @Test
    void anUnreadableSavedTokenIsExplainedNotAnError() throws Exception {
        long id = create(GOOD);
        var project = projectRepository.findById(id).orElseThrow();
        project.setRepoTokenEncrypted("v1:not-a-real-ciphertext");
        projectRepository.saveAndFlush(project);

        mockMvc.perform(get("/api/projects/{id}/env/requirements", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0]").value(containsString("can't be read")));
        mockMvc.perform(post("/api/projects/detect").contentType(MediaType.APPLICATION_JSON).content("""
                        {"repositoryUrl":"https://github.com/acme/secret-app","branch":"main","projectId":%d}""".formatted(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0]").value(containsString("can't be read")));
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
