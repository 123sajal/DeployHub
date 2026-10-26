package com.deployhub.platform.execution;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.deployment.DeploymentLogWriter;
import com.deployhub.platform.models.enums.ApplicationType;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.worker.DeploymentStepException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.invocation.Invocation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/**
 * Real git + real Docker: builds a tiny Node.js app from a local repository, runs it with the production
 * limits, calls it over HTTP and removes it again. Run with {@code ./mvnw test -Pdocker-it} (Docker must be
 * running; the first run pulls node:22-alpine).
 */
@Tag("docker")
class DockerExecutorDockerTest {

    private static final long PROJECT_ID = 990_001L;

    @TempDir Path temp;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();
    private DockerDeploymentExecutor executor;
    private Path repository;
    private DeploymentLogWriter logs;

    @BeforeEach
    void setUp() {
        repository = temp.resolve("origin");
        git(temp, "init", "-q", "-b", "main", repository.toString());
        write(repository.resolve("server.js"), """
                const fs = require('fs');
                fs.mkdirSync(__dirname + '/uploads', { recursive: true }); // apps may write into their own folder
                require('http').createServer((req, res) => res.end('hello from ' + process.getuid()))
                  .listen(process.env.PORT, '0.0.0.0');
                """);
        write(repository.resolve("crash.js"), "process.exit(3);\n");
        write(repository.resolve("api.js"), """
                require('http').createServer((req, res) => res.end('api ' + req.url + ' ' + process.env.GREETING))
                  .listen(process.env.PORT, '0.0.0.0');
                """);
        git(repository, "add", ".");
        git(repository, "commit", "-q", "-m", "app");
        executor = new DockerDeploymentExecutor(runner, new HttpAppProbe(),
                new DeployHubProperties.Execution(ExecutionMode.REAL, "docker", "git", temp.resolve("work").toString(),
                        Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofSeconds(60), 21000, 21099, "256m",
                        "0.5", 128, 2000, 3), true);
        logs = mock(DeploymentLogWriter.class);
    }

    @AfterEach
    void cleanUp() {
        executor.stop(DockerDeploymentExecutor.containerName(1L));
        executor.stop(DockerDeploymentExecutor.containerName(2L));
        executor.unpublish(PROJECT_ID);
        executor.pruneImages(PROJECT_ID, Set.of());
        runner.shutdown();
    }

    private DeploymentSpec spec(long id, String startCommand, String imageRef) {
        return new DeploymentSpec(id, DeploymentStatus.BUILDING, ExecutionMode.REAL, 1, PROJECT_ID, "docker-it",
                repository.toUri().toString(), "main", ApplicationType.NODE_JS, null, startCommand, 3000, "/", null,
                imageRef);
    }

    private ExecutionContext context(long id) {
        return new ExecutionContext(id, logs, 2000);
    }

    @Test
    void buildsRunsAndRemovesARealApp() throws Exception {
        DeploymentExecutor.BuildResult built = executor.build(spec(1L, "node server.js", null), context(1L));
        DeploymentExecutor.RunResult run = executor.start(spec(1L, "node server.js", built.imageRef()), context(1L));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + run.hostPort() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).isEqualTo("hello from 10001"); // runs as the unprivileged user
        assertThat(docker("inspect", "--format", "{{.HostConfig.Memory}} {{.HostConfig.PidsLimit}} "
                + "{{.HostConfig.CapDrop}} {{.HostConfig.SecurityOpt}}", run.containerName()).getLast())
                .isEqualTo("268435456 128 [ALL] [no-new-privileges]");

        // Same commit, same settings: the second build reuses the image.
        executor.build(spec(2L, "node server.js", null), context(2L));
        assertThat(loggedMessages()).anyMatch(m -> m.startsWith("Reusing image " + built.imageRef()));

        executor.stop(run.containerName());
        assertThat(docker("ps", "--all", "--quiet", "--filter", "name=" + run.containerName())).isEmpty();
    }

    /**
     * Regression (BUG-14): the build runs as root. If it wrote into the home folder the app later uses (e.g. npm's
     * cache in $HOME/.npm), the app's unprivileged user couldn't write there and "npx serve" failed with EACCES.
     */
    @Test
    void whatTheBuildLeavesInItsHomeFolderDoesNotBlockTheAppsHomeFolder() throws Exception {
        DeploymentSpec base = spec(1L, "mkdir -p $HOME/.npm/runtime && node server.js", null);
        DeploymentSpec withBuild = new DeploymentSpec(base.deploymentId(), base.status(), base.mode(), base.attempt(),
                base.projectId(), base.projectName(), base.repositoryUrl(), base.ref(), base.appType(),
                "mkdir -p $HOME/.npm/_cacache && touch $HOME/.npm/_cacache/root-owned", base.startCommand(),
                base.appPort(), base.healthCheckPath(), base.resolvedCommit(), base.imageRef());

        DeploymentExecutor.BuildResult built = executor.build(withBuild, context(1L));
        DeploymentExecutor.RunResult run = executor.start(new DeploymentSpec(withBuild.deploymentId(),
                withBuild.status(), withBuild.mode(), withBuild.attempt(), withBuild.projectId(),
                withBuild.projectName(), withBuild.repositoryUrl(), withBuild.ref(), withBuild.appType(),
                withBuild.buildCommand(), withBuild.startCommand(), withBuild.appPort(), withBuild.healthCheckPath(),
                withBuild.resolvedCommit(), built.imageRef()), context(1L));

        assertThat(getWithRetry(run.hostPort(), "/")).isEqualTo("hello from 10001");
    }

    @Test
    void anAppThatCrashesFailsTheDeploymentAndLeavesNothingRunning() {
        DeploymentExecutor.BuildResult built = executor.build(spec(1L, "node crash.js", null), context(1L));

        assertThatThrownBy(() -> executor.start(spec(1L, "node crash.js", built.imageRef()), context(1L)))
                .isInstanceOfSatisfying(DeploymentStepException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).contains("exit code 3");
                });
        assertThat(docker("ps", "--all", "--quiet", "--filter", "name=deployhub-d1$")).isEmpty();
    }

    @Test
    void aProjectAddressServesTheAppAndRoutesApiToAnotherAppWithItsEnvironment() throws Exception {
        DeploymentExecutor.BuildResult web = executor.build(spec(1L, "node server.js", null), context(1L));
        DeploymentExecutor.BuildResult api = executor.build(spec(2L, "node api.js", null), context(2L));
        DeploymentExecutor.RunResult webRun = executor.start(spec(1L, "node server.js", web.imageRef()), context(1L));
        DeploymentSpec apiSpec = spec(2L, "node api.js", api.imageRef());
        DeploymentExecutor.RunResult apiRun = executor.start(new DeploymentSpec(apiSpec.deploymentId(), apiSpec.status(),
                apiSpec.mode(), apiSpec.attempt(), apiSpec.projectId(), apiSpec.projectName(), apiSpec.repositoryUrl(),
                apiSpec.ref(), apiSpec.appType(), apiSpec.buildCommand(), apiSpec.startCommand(), apiSpec.appPort(),
                apiSpec.healthCheckPath(), apiSpec.resolvedCommit(), apiSpec.imageRef(), Map.of("GREETING", "hi-there")),
                context(2L));

        int address = 21190;
        executor.publish(new DeploymentExecutor.Gateway(PROJECT_ID, "web", address, webRun.containerName(), 3000,
                List.of(new DeploymentExecutor.Route("/api", apiRun.containerName(), 3000, "api"))));

        assertThat(getWithRetry(address, "/")).isEqualTo("hello from 10001");
        assertThat(getWithRetry(address, "/api/users?page=2")).isEqualTo("api /api/users?page=2 hi-there");
        assertThat(getWithRetry(address, "/api")).isEqualTo("api /api hi-there");

        // Re-publishing (as on every go-live) reloads in place; with no live app, "/" explains instead of failing.
        executor.publish(new DeploymentExecutor.Gateway(PROJECT_ID, "web", address, null, null,
                List.of(new DeploymentExecutor.Route("/api", apiRun.containerName(), 3000, "api"))));
        assertThat(awaitBody(address, "/", "No live deployment of project web")).isTrue();
        assertThat(getWithRetry(address, "/api/x")).isEqualTo("api /api/x hi-there");

        executor.unpublish(PROJECT_ID);
        assertThat(docker("ps", "--all", "--quiet", "--filter", "name=deployhub-edge-p" + PROJECT_ID)).isEmpty();
    }

    /** A reload is applied by new nginx workers while old ones finish, so the switch takes a moment. */
    private static boolean awaitBody(int port, String path, String expected) throws Exception {
        for (int i = 0; i < 50; i++) {
            if (getWithRetry(port, path).equals(expected)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    /** The proxy needs a moment after (re)starting. */
    private static String getWithRetry(int port, String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        Exception last = null;
        for (int i = 0; i < 50; i++) {
            try {
                return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                        HttpResponse.BodyHandlers.ofString()).body().trim();
            } catch (java.io.IOException e) {
                last = e;
                Thread.sleep(200);
            }
        }
        throw last;
    }

    private List<String> loggedMessages() {
        List<String> messages = new ArrayList<>();
        for (Invocation invocation : mockingDetails(logs).getInvocations()) {
            messages.add(String.valueOf(invocation.getArguments()[1]));
        }
        return messages;
    }

    private List<String> docker(String... args) {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(args));
        List<String> output = new ArrayList<>();
        runner.run(command, Map.of(), null, Duration.ofSeconds(30), null, output::add);
        return output;
    }

    private void git(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=Test", "-c", "user.email=test@example.com",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        assertThat(runner.run(command, Map.of(), dir, Duration.ofSeconds(30), null, line -> { }).ok()).isTrue();
    }

    private static void write(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
