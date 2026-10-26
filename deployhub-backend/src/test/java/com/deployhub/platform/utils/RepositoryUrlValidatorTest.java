package com.deployhub.platform.utils;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryUrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/spring-projects/spring-petclinic",
            "https://github.com/spring-projects/spring-petclinic.git",
            "https://GitHub.com/owner/repo/",
            "https://gitlab.com/group/subgroup/repo",
            "https://bitbucket.org/team/repo"
    })
    void acceptsPublicGitHostUrls(String url) {
        assertThat(RepositoryUrlValidator.isAllowed(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://github.com/owner/repo",                       // not TLS
            "https://169.254.169.254/latest/meta-data",           // cloud metadata endpoint
            "https://localhost/owner/repo",
            "https://127.0.0.1/owner/repo",
            "https://10.0.0.5/owner/repo",
            "https://github.com.evil.example/owner/repo",          // look-alike host
            "https://user:token@github.com/owner/repo",            // embedded credentials
            "https://github.com:8443/owner/repo",                  // non-default port
            "https://github.com/owner/repo?ref=x",
            "https://github.com/owner/repo#frag",
            "https://github.com/owner",                            // not a repository path
            "https://github.com/owner/../../etc",
            "file:///etc/passwd",
            "git@github.com:owner/repo.git",
            "not a url"
    })
    void rejectsUnsafeOrUnsupportedUrls(String url) {
        assertThat(RepositoryUrlValidator.isAllowed(url)).isFalse();
    }
}
