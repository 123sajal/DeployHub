package com.deployhub.platform.auth;

import com.deployhub.platform.support.IntegrationTestSupport;
import com.deployhub.platform.support.OutageProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A real network outage: the app reaches its database through {@link OutageProxy}. Cutting the
 * proxy drops every connection and refuses new ones, as when the database host goes away.
 * Logins must then fail with 503 ("try again") - never a misleading 401, never a skipped password check.
 */
@Timeout(90)
@TestPropertySource(properties = "spring.datasource.hikari.connection-timeout=500")
abstract class DatabaseOutageTestBase extends IntegrationTestSupport {

    protected abstract OutageProxy proxy();

    /** Whether this database survives a dropped network connection so recovery can be checked. */
    protected abstract boolean verifiesRecovery();

    @AfterEach
    void restoreNetwork() throws IOException {
        proxy().restore();
    }

    private String loginJson() {
        return """
                {"email":"alice@example.com","password":"%s"}""".formatted(PASSWORD);
    }

    @Test
    void authReportsServiceUnavailableWhileTheDatabaseIsDown() throws Exception {
        proxy().cut();

        anonymousMvc.perform(post("/api/auth/login").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Service temporarily unavailable"));
        anonymousMvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"New","email":"new@example.com","password":"%s"}""".formatted(PASSWORD)))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/projects")).andExpect(status().isServiceUnavailable());
        anonymousMvc.perform(get("/api/health")).andExpect(status().isServiceUnavailable());

        // An existing session is still recognised (it lives in server memory) and logout still works.
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());

        if (verifiesRecovery()) {
            proxy().restore();
            assertLoginRecoversWithoutRestart();
        }
    }

    /**
     * Right after the network returns, the pool may still hand out a connection that died during the
     * outage (one more 503) before discarding it. A real client retries; the app must heal by itself
     * within seconds, without a restart.
     */
    private void assertLoginRecoversWithoutRestart() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        int lastStatus = -1;
        while (System.currentTimeMillis() < deadline) {
            lastStatus = anonymousMvc.perform(post("/api/auth/login").with(xsrf())
                            .contentType(MediaType.APPLICATION_JSON).content(loginJson()))
                    .andReturn().getResponse().getStatus();
            if (lastStatus == 200) {
                return;
            }
            assertThat(lastStatus).as("while recovering, only 503 is acceptable").isEqualTo(503);
            Thread.sleep(250);
        }
        throw new AssertionError("Login did not recover within 30 s; last status " + lastStatus);
    }
}
