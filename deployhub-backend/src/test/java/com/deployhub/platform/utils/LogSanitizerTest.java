package com.deployhub.platform.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    @Test
    void masksKeyValueSecrets() {
        String out = LogSanitizer.sanitize("API_TOKEN=abc123 DB_PASSWORD='p@ss word' npm run build");
        assertThat(out).isEqualTo("API_TOKEN=*** DB_PASSWORD=*** npm run build");
    }

    @Test
    void masksUrlCredentialsAndTokens() {
        assertThat(LogSanitizer.sanitize("git clone https://bob:hunter2@github.com/a/b"))
                .isEqualTo("git clone https://***:***@github.com/a/b");
        assertThat(LogSanitizer.sanitize("Authorization: Bearer eyJhbGciOi.payload.sig"))
                .doesNotContain("eyJhbGciOi");
        assertThat(LogSanitizer.sanitize("using ghp_abcdefghijklmnopqrstuvwxyz0123456789"))
                .isEqualTo("using ***");
        assertThat(LogSanitizer.sanitize("key AKIAABCDEFGHIJKLMNOP")).isEqualTo("key ***");
    }

    @Test
    void leavesOrdinaryTextAloneAndCapsLength() {
        assertThat(LogSanitizer.sanitize("Build finished in 3s")).isEqualTo("Build finished in 3s");
        assertThat(LogSanitizer.sanitize("x".repeat(5000))).hasSize(LogSanitizer.MAX_MESSAGE_LENGTH);
        assertThat(LogSanitizer.sanitize(null)).isEmpty();
    }
}
