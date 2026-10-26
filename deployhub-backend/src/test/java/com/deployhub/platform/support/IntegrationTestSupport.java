package com.deployhub.platform.support;

import com.deployhub.platform.models.entities.ProjectMaster;
import com.deployhub.platform.models.entities.UserMaster;
import com.deployhub.platform.models.enums.AccountStatus;
import com.deployhub.platform.models.enums.ApplicationType;
import com.deployhub.platform.repositories.AuditEventRepository;
import com.deployhub.platform.repositories.DeploymentJobRepository;
import com.deployhub.platform.repositories.DeploymentLogRepository;
import com.deployhub.platform.repositories.DeploymentStatusHistoryRepository;
import com.deployhub.platform.repositories.IdempotencyRecordRepository;
import com.deployhub.platform.repositories.DeploymentRepository;
import com.deployhub.platform.repositories.ProjectRepository;
import com.deployhub.platform.repositories.UserRepository;
import com.deployhub.platform.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Spring context (with the full security filter chain) + H2 (Oracle mode) + Flyway.
 * Before each test the database is emptied and "Alice" signs up through the real
 * /api/auth/register endpoint; {@link #mockMvc} then sends every request with Alice's session
 * and a valid CSRF token. {@link #anonymousMvc} has no session at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    protected static final String PASSWORD = "correct-horse-battery";

    public record TestUser(Long id, String email, MockHttpSession session) {
    }

    @Autowired protected WebApplicationContext context;
    @Autowired protected MockMvc anonymousMvc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected ProjectRepository projectRepository;
    @Autowired protected DeploymentRepository deploymentRepository;
    @Autowired protected DeploymentLogRepository deploymentLogRepository;
    @Autowired protected AuditEventRepository auditEventRepository;
    @Autowired protected DeploymentJobRepository jobRepository;
    @Autowired protected DeploymentStatusHistoryRepository statusHistoryRepository;
    @Autowired protected IdempotencyRecordRepository idempotencyRecordRepository;

    protected TestUser alice;
    /** Requests as Alice (session + CSRF token). */
    protected MockMvc mockMvc;

    @BeforeEach
    void resetDatabaseAndSignInAlice() throws Exception {
        auditEventRepository.deleteAllInBatch();
        idempotencyRecordRepository.deleteAllInBatch();
        statusHistoryRepository.deleteAllInBatch();
        jobRepository.deleteAllInBatch();
        deploymentLogRepository.deleteAllInBatch();
        deploymentRepository.deleteAllInBatch();
        projectRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        alice = registerUser("Alice", "alice@example.com");
        mockMvc = mvcAs(alice);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * What the Angular app does: send the XSRF-TOKEN cookie and echo it in the X-XSRF-TOKEN header.
     * Used instead of spring-security-test's csrf(), which swaps out the app's real cookie
     * repository for the whole (shared) test context and would hide cookie bugs.
     */
    protected static RequestPostProcessor xsrf() {
        return request -> {
            String token = UUID.randomUUID().toString();
            List<Cookie> cookies = new ArrayList<>(request.getCookies() == null ? List.of() : Arrays.asList(request.getCookies()));
            cookies.removeIf(c -> c.getName().equals("XSRF-TOKEN"));
            cookies.add(new Cookie("XSRF-TOKEN", token));
            request.setCookies(cookies.toArray(Cookie[]::new));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    protected TestUser registerUser(String name, String email) throws Exception {
        MvcResult result = anonymousMvc.perform(post("/api/auth/register").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"%s","email":"%s","password":"%s"}
                                """.formatted(name, email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        return new TestUser(idFrom(result), email, (MockHttpSession) result.getRequest().getSession(false));
    }

    /** A MockMvc that sends every request with this user's session and a CSRF token. */
    protected MockMvc mvcAs(TestUser user) {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").session(user.session()).with(xsrf()))
                .build();
    }

    /** For tests that call services directly (no HTTP request): act as this user. */
    protected void actAs(TestUser user) {
        AuthenticatedUser principal = new AuthenticatedUser(user.id(), user.email(), "test", null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    protected static String projectJson(String name, String buildCommand) {
        return """
                {"name":"%s","repositoryUrl":"https://github.com/acme/%s","branch":"main",
                 "appType":"NODE_JS","buildCommand":"%s","startCommand":"node server.js"}
                """.formatted(name, name, buildCommand);
    }

    protected long createProject(String name, String buildCommand) throws Exception {
        return createProject(mockMvc, name, buildCommand);
    }

    protected long createProject(MockMvc mvc, String name, String buildCommand) throws Exception {
        MvcResult result = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson(name, buildCommand)))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    protected long createDeployment(long projectId) throws Exception {
        return createDeployment(mockMvc, projectId);
    }

    protected long createDeployment(MockMvc mvc, long projectId) throws Exception {
        MvcResult result = mvc.perform(post("/api/projects/{id}/deployments", projectId))
                .andExpect(status().isAccepted())
                .andReturn();
        return idFrom(result);
    }

    /** A project owned by somebody else, inserted directly into the database. */
    protected ProjectMaster otherUsersProject() {
        Instant now = Instant.now();
        UserMaster other = new UserMaster();
        other.setEmail("someone-else@example.com");
        other.setDisplayName("Someone Else");
        other.setStatus(AccountStatus.ACTIVE);
        other.setCreatedAt(now);
        other.setUpdatedAt(now);
        userRepository.save(other);

        ProjectMaster project = new ProjectMaster();
        project.setOwner(other);
        project.setName("private-project");
        project.setRepositoryUrl("https://github.com/else/private-project");
        project.setBranch("main");
        project.setAppType(ApplicationType.STATIC_SITE);
        project.setStartCommand("serve");
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        return projectRepository.save(project);
    }

    protected static long idFrom(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        var matcher = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("No id in response: " + body);
        }
        return Long.parseLong(matcher.group(1));
    }
}
