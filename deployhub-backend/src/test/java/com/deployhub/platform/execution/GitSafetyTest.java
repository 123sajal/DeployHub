package com.deployhub.platform.execution;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class GitSafetyTest {

    @Test
    void credentialsGoOnlyToTheRepositorysHostWithEachHostsUserName() {
        var github = GitSafety.authEnvironment("https://github.com/acme/app.git", " github_pat_x ");
        assertThat(github.get("GIT_CONFIG_KEY_0")).isEqualTo("http.https://github.com/.extraheader");
        assertThat(decode(github.get("GIT_CONFIG_VALUE_0"))).isEqualTo("x-access-token:github_pat_x");

        assertThat(decode(GitSafety.authEnvironment("https://gitlab.com/acme/app", "glpat-y").get("GIT_CONFIG_VALUE_0")))
                .isEqualTo("oauth2:glpat-y");
        assertThat(decode(GitSafety.authEnvironment("https://bitbucket.org/acme/app", "z").get("GIT_CONFIG_VALUE_0")))
                .isEqualTo("x-token-auth:z");
        assertThat(GitSafety.authEnvironment("https://github.com/acme/app", null)).isEmpty();
        assertThat(GitSafety.authEnvironment("https://github.com/acme/app", "  ")).isEmpty();
    }

    private static String decode(String header) {
        return new String(Base64.getDecoder().decode(header.substring("Authorization: Basic ".length())),
                StandardCharsets.UTF_8);
    }
}
