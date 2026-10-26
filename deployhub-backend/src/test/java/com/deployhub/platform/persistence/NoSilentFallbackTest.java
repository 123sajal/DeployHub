package com.deployhub.platform.persistence;

import com.deployhub.platform.DeployhubBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** If the configured database is unreachable the app must refuse to start, never fall back to H2. */
class NoSilentFallbackTest {

    @Test
    void oracleProfileFailsToStartWhenDatabaseIsUnreachable() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(DeployhubBackendApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.profiles.active=oracle",
                        "--DB_URL=jdbc:oracle:thin:@//127.0.0.1:1/NO_SUCH_SERVICE",
                        "--DB_USERNAME=nobody",
                        "--DB_PASSWORD=nothing",
                        "--spring.datasource.hikari.connection-timeout=3000"))
                .hasStackTraceContaining("127.0.0.1");
    }
}
