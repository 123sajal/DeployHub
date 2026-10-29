package com.deployhub.platform.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.sql.DataSource;
import java.sql.Connection;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUG-21: on an H2 file database (the local profile, used by the demo copy), H2 2.4.240 broke CHECK
 * constraints once the connection pool replaced its connections (Hikari does that every 30 minutes): every insert into
 * users or email_codes then failed with "Check constraint invalid", so sign-up and email codes stopped working.
 * The pom pins H2 2.3.232, which doesn't have this bug. This test replaces the pooled connections while one stays open
 * (as the real pool does) and signs up afterwards; it fails on H2 2.4.240.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class H2FileConstraintsAfterMigrationTest {

    private static final Path DIR = createDir();

    private static Path createDir() {
        try {
            return Files.createTempDirectory("deployhub-h2-file-");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void fileDatabase(DynamicPropertyRegistry registry) {
        // Same shape as application-local.properties: a file database in Oracle mode.
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:" + DIR.resolve("deployhub") + ";MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH");
    }

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    private static RequestPostProcessor xsrf() {
        return request -> {
            String token = UUID.randomUUID().toString();
            ((MockHttpServletRequest) request).setCookies(new Cookie("XSRF-TOKEN", token));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    @Test
    void signUpStillWorksAfterThePoolReplacesItsConnections() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        // The real pool replaces its connections one at a time, so the database never closes in between: hold one
        // connection open while every other one is replaced (retiring all at once would close and heal the database).
        try (Connection held = pool.getConnection()) {
            pool.getHikariPoolMXBean().softEvictConnections();
            Thread.sleep(300);

            signUpAndSendACode();
        }
    }

    private void signUpAndSendACode() throws Exception {
        mvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"After Eviction\",\"email\":\"after.eviction@example.com\","
                                + "\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/assistant/signup/email-code").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new.person@example.com\"}"))
                .andExpect(status().isAccepted());
    }
}
