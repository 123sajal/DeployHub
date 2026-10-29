package com.deployhub.platform.auth;

import com.deployhub.platform.models.entities.UserMaster;
import com.deployhub.platform.models.enums.AccountStatus;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class AuthApiIntegrationTest extends IntegrationTestSupport {

    private static String registerJson(String name, String email, String password) {
        return """
                {"displayName":"%s","email":"%s","password":"%s"}
                """.formatted(name, email, password);
    }

    private static String loginJson(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private ResultActions login(String email, String password, RequestPostProcessor... extras) throws Exception {
        var request = post("/api/auth/login").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(email, password));
        for (RequestPostProcessor extra : extras) {
            request = request.with(extra);
        }
        return anonymousMvc.perform(request);
    }

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    // ---- 1. registration ----

    @Test
    void registrationCreatesAccountWithHashedPasswordAndLogsIn() throws Exception {
        MvcResult result = anonymousMvc.perform(post("/api/auth/register").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Bob", "  Bob.Builder@Example.COM ", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("bob.builder@example.com"))
                .andExpect(jsonPath("$.displayName").value("Bob"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        UserMaster saved = userRepository.findByEmail("bob.builder@example.com").orElseThrow();
        assertThat(saved.getPasswordHash()).startsWith("{bcrypt}$2").doesNotContain(PASSWORD);
        assertThat(saved.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        // Registration also signs the user in.
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        anonymousMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("bob.builder@example.com"));
    }

    // ---- 2. validation ----

    @Test
    void namesInAnyLanguageAreAcceptedAndExtraSpacesRemoved() throws Exception {
        anonymousMvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("  राहुल   Verma ", "hindi-name@example.com", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("राहुल Verma"));
    }

    @Test
    void registrationRejectsInvalidInput() throws Exception {
        String tooManyBytes = "🔒".repeat(20); // 20 characters but 80 UTF-8 bytes > BCrypt's 72
        anonymousMvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("", "not-an-email", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.displayName").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());

        // Names: letters and spaces only, in any language.
        for (String badName : new String[]{"Bob1", "Bob_Builder", "Bob!", "123", "Bob.Builder"}) {
            anonymousMvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(registerJson(badName, "name-check@example.com", PASSWORD)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.displayName").value(containsString("only letters and spaces")));
        }
        assertThat(userRepository.findByEmail("name-check@example.com")).isEmpty();

        for (String badEmail : new String[]{"a@b", "two@@example.com", "no spaces@example.com"}) {
            anonymousMvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(registerJson("X", badEmail, PASSWORD)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.email").exists());
        }

        anonymousMvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("X", "x@example.com", tooManyBytes)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());

        anonymousMvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("X", "same-as-email@example.com", "same-as-email@example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("must not be the same as your email"));

        assertThat(userRepository.count()).as("only Alice exists").isEqualTo(1);
    }

    // ---- 3. duplicates ----

    @Test
    void duplicateRegistrationIsRejectedEvenWithDifferentCase() throws Exception {
        anonymousMvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Imposter", " ALICE@example.com", "another-password-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("An account with this email already exists"));
        assertThat(userRepository.count()).isEqualTo(1);
    }

    // ---- 4/5. login ----

    @Test
    void loginWithCorrectCredentialsStartsASession() throws Exception {
        MvcResult result = login("  ALICE@example.com", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);

        anonymousMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        assertThat(userRepository.findByEmail("alice@example.com").orElseThrow().getLastLoginAt()).isNotNull();
        assertThat(auditEventRepository.findByUserIdOrderByIdAsc(alice.id()))
                .extracting(e -> e.getType())
                .contains(AuditEventType.REGISTERED, AuditEventType.LOGIN_SUCCEEDED);
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameAnswer() throws Exception {
        String wrongPassword = login("alice@example.com", "wrong-password-123")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownUser = login("nobody@example.com", "wrong-password-123")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(unknownUser).contains("Invalid email or password");
    }

    @Test
    void accountsWithoutPasswordOrDisabledCannotLogIn() throws Exception {
        Instant now = Instant.now();
        UserMaster legacy = new UserMaster();
        legacy.setEmail("legacy@example.com");
        legacy.setDisplayName("Legacy");
        legacy.setStatus(AccountStatus.LEGACY_NO_PASSWORD);
        legacy.setCreatedAt(now);
        legacy.setUpdatedAt(now);
        userRepository.save(legacy);
        login("legacy@example.com", "anything-at-all").andExpect(status().isUnauthorized());

        UserMaster aliceRow = userRepository.findByEmail("alice@example.com").orElseThrow();
        aliceRow.setStatus(AccountStatus.DISABLED);
        userRepository.save(aliceRow);
        login("alice@example.com", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password."));
    }

    // ---- 6. logout ----

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());

        assertThat(alice.session().isInvalid()).isTrue();
        anonymousMvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        assertThat(auditEventRepository.findByUserIdOrderByIdAsc(alice.id()))
                .extracting(e -> e.getType()).contains(AuditEventType.LOGGED_OUT);
    }

    /**
     * Regression test for "Your request was blocked" after sign-up → log out → log in in the same tab.
     * Follows the browser exactly: only the cookies the server hands out, no test shortcuts.
     */
    @Test
    void canLogInAgainRightAfterLoggingOutWithoutReloading() throws Exception {
        Cookie xsrf = anonymousMvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        MvcResult signedUp = anonymousMvc.perform(post("/api/auth/register").cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Carol", "carol@example.com", PASSWORD)))
                .andExpect(status().isCreated()).andReturn();
        MockHttpSession session = (MockHttpSession) signedUp.getRequest().getSession(false);
        xsrf = latestXsrfCookie(signedUp, xsrf);

        MvcResult loggedOut = anonymousMvc.perform(post("/api/auth/logout").session(session).cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent()).andReturn();
        Cookie afterLogout = latestXsrfCookie(loggedOut, null);
        assertThat(afterLogout).as("logout must hand out a fresh token, not just delete the old one").isNotNull();

        anonymousMvc.perform(post("/api/auth/login").cookie(afterLogout)
                        .header("X-XSRF-TOKEN", afterLogout.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("carol@example.com", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("carol@example.com"));
    }

    @Test
    void csrfEndpointIsPublicAndIssuesAToken() throws Exception {
        MvcResult result = anonymousMvc.perform(get("/api/auth/csrf")).andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNotNull();
    }

    /** The last non-empty XSRF-TOKEN cookie set by a response (a browser keeps the last one), else the fallback. */
    private static Cookie latestXsrfCookie(MvcResult result, Cookie fallback) {
        Cookie latest = fallback;
        for (Cookie c : result.getResponse().getCookies()) {
            if (c.getName().equals("XSRF-TOKEN")) {
                latest = c.getValue().isEmpty() ? null : c;
            }
        }
        return latest;
    }

    // ---- 7. protected endpoints ----

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/me", "/api/projects", "/api/projects/1", "/api/deployments",
            "/api/deployments/1", "/api/deployments/1/logs", "/api/dashboard/summary"})
    void protectedEndpointsRequireLogin(String path) throws Exception {
        anonymousMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Authentication required"));
    }

    @Test
    void writesWithoutLoginAreRejected() throws Exception {
        anonymousMvc.perform(post("/api/projects").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("anon", "x")))
                .andExpect(status().isUnauthorized());
        assertThat(projectRepository.count()).isZero();
    }

    @Test
    void healthStaysPublic() throws Exception {
        anonymousMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    // ---- 8. "page refresh": the same session cookie restores the user ----

    @Test
    void sessionKeepsUserAcrossRequests() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(alice.id()));
        }
    }

    // ---- CSRF, cookies and session fixation ----

    @Test
    void writesWithoutCsrfTokenAreRejected() throws Exception {
        anonymousMvc.perform(post("/api/projects").session(alice.session())
                        .contentType(MediaType.APPLICATION_JSON).content(projectJson("no-csrf", "x")))
                .andExpect(status().isForbidden());
        anonymousMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("alice@example.com", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    /** The exact flow the Angular app uses: read XSRF-TOKEN cookie, echo it in X-XSRF-TOKEN. */
    @Test
    void spaCsrfCookieFlowWorksAndTokenRotatesOnLogin() throws Exception {
        MvcResult first = anonymousMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andReturn();
        Cookie xsrf = first.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).as("XSRF-TOKEN cookie issued on first contact").isNotNull();
        assertThat(xsrf.isHttpOnly()).as("Angular must be able to read it").isFalse();
        assertThat(xsrf.getAttribute("SameSite")).isEqualTo("Lax");

        MvcResult loggedIn = anonymousMvc.perform(post("/api/auth/login")
                        .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(loginJson("alice@example.com", PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie rotated = Arrays.stream(loggedIn.getResponse().getCookies())
                .filter(c -> c.getName().equals("XSRF-TOKEN") && !c.getValue().isEmpty())
                .findFirst().orElseThrow();
        assertThat(rotated.getValue()).isNotEqualTo(xsrf.getValue());

        // The browser now uses the new cookie for its next write.
        MockHttpSession session = (MockHttpSession) loggedIn.getRequest().getSession(false);
        anonymousMvc.perform(post("/api/projects").session(session)
                        .cookie(rotated).header("X-XSRF-TOKEN", rotated.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(projectJson("via-spa-flow", "x")))
                .andExpect(status().isCreated());
        // A header that doesn't match the cookie (what a cross-site attacker would have to guess) fails.
        anonymousMvc.perform(post("/api/projects").session(session)
                        .cookie(rotated).header("X-XSRF-TOKEN", "guessed-value")
                        .contentType(MediaType.APPLICATION_JSON).content(projectJson("forged", "x")))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginIssuesANewSessionIdToPreventFixation() throws Exception {
        MockHttpSession planted = new MockHttpSession(null, "attacker-chosen-id");
        // As in a real request, the browser *sent* this session id (e.g. planted by an attacker).
        RequestPostProcessor sentByBrowser = request -> {
            request.setRequestedSessionId("attacker-chosen-id");
            request.setRequestedSessionIdValid(true);
            return request;
        };
        MvcResult result = anonymousMvc.perform(post("/api/auth/login").session(planted).with(sentByBrowser).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content(loginJson("alice@example.com", PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getRequest().getSession(false).getId()).isNotEqualTo("attacker-chosen-id");
    }

    // ---- throttling ----

    @Test
    void repeatedFailuresAreThrottledPerAccountAndIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("alice@example.com", "wrong-password-" + i, fromIp("203.0.113.7")).andExpect(status().isUnauthorized());
        }
        // Even the right password is refused now, from that address...
        login("alice@example.com", PASSWORD, fromIp("203.0.113.7"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"));
        // ...but the real Alice on another network is not locked out.
        login("alice@example.com", PASSWORD, fromIp("198.51.100.20")).andExpect(status().isOk());

        assertThat(auditEventRepository.findAll()).extracting(e -> e.getType())
                .contains(AuditEventType.LOGIN_FAILED, AuditEventType.LOGIN_THROTTLED);
    }

    // ---- secrets never leak ----

    @Test
    void passwordsNeverAppearInAuditRowsOrApplicationLogs(CapturedOutput output) throws Exception {
        String secret = "Sup3r-Secret-Value-77";
        anonymousMvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                .content(registerJson("Leak Test", "leak@example.com", secret))).andExpect(status().isCreated());
        login("leak@example.com", "wrong-" + secret).andExpect(status().isUnauthorized());
        login("leak@example.com", secret).andExpect(status().isOk());

        assertThat(auditEventRepository.findAll()).allSatisfy(e -> {
            assertThat(String.valueOf(e.getDetails())).doesNotContain(secret);
            assertThat(String.valueOf(e.getSubject())).doesNotContain(secret);
        });
        assertThat(output.getAll()).doesNotContain(secret);
    }
}
