package com.deployhub.platform.execution;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.deployment.DeploymentLogWriter;
import com.deployhub.platform.models.enums.ApplicationType;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.worker.DeploymentStepException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Real git, against a local repository (file://, allowed only through the test constructor), with the
 * same hardened flags and environment as production. Docker is faked: the build "reuses" an image.
 */
class GitFetchTest {

    @TempDir Path temp;

    private final ProcessCommandRunner real = new ProcessCommandRunner();
    private Path repository;
    private String firstCommit;
    private String secondCommit;

    @BeforeEach
    void createRepository() {
        repository = temp.resolve("origin");
        git(temp, "init", "-q", "-b", "main", repository.toString());
        write(repository.resolve("server.js"), "console.log('v1')\n");
        git(repository, "add", ".");
        git(repository, "commit", "-q", "-m", "v1");
        firstCommit = git(repository, "rev-parse", "HEAD").getLast();
        write(repository.resolve("server.js"), "console.log('v2')\n");
        git(repository, "commit", "-q", "-am", "v2");
        secondCommit = git(repository, "rev-parse", "HEAD").getLast();
    }

    @AfterEach
    void stop() {
        real.shutdown();
    }

    /** Real git; docker image inspect "finds" the image, so no Docker is needed. */
    private DockerDeploymentExecutor executor() {
        CommandRunner gitOnly = new CommandRunner() {
            @Override
            public Result run(List<String> command, Map<String, String> env, Path workingDir, Duration timeout,
                              ExecutionContext context, Consumer<String> onLine) {
                return command.getFirst().equals("docker")
                        ? new Result(0, false, List.of())
                        : real.run(command, env, workingDir, timeout, context, onLine);
            }
        };
        DeployHubProperties.Execution settings = new DeployHubProperties.Execution(ExecutionMode.REAL, "docker", "git",
                temp.resolve("work").toString(), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofSeconds(5),
                20000, 20010, "512m", "1.0", 256, 1000, 3);
        return new DockerDeploymentExecutor(gitOnly, (port, path) -> 200, settings, true);
    }

    private DeploymentExecutor.BuildResult build(String ref) {
        DeploymentSpec spec = new DeploymentSpec(1L, DeploymentStatus.BUILDING, ExecutionMode.REAL, 1, 1L, "app",
                repository.toUri().toString(), ref, ApplicationType.NODE_JS, null, "node server.js", 3000, null, null,
                null);
        return executor().build(spec, new ExecutionContext(1L, mock(DeploymentLogWriter.class), 1000));
    }

    @Test
    void fetchingABranchRecordsTheExactCommitItPointsTo() {
        assertThat(build("main").resolvedCommit()).isEqualTo(secondCommit);
    }

    @Test
    void aFullCommitShaFetchesExactlyThatCommit() {
        assertThat(build(firstCommit).resolvedCommit()).isEqualTo(firstCommit);
    }

    @Test
    void anUnknownBranchIsAPermanentError() {
        assertThatThrownBy(() -> build("does-not-exist"))
                .isInstanceOfSatisfying(DeploymentStepException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).contains("'does-not-exist' was not found");
                });
    }

    @Test
    void localRepositoriesAreRefusedInProduction() {
        DockerDeploymentExecutor production = new DockerDeploymentExecutor(real, (port, path) -> 200,
                new DeployHubProperties.Execution(ExecutionMode.REAL, "docker", "git", temp.resolve("w").toString(),
                        Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofSeconds(5), 20000, 20010, "512m",
                        "1.0", 256, 1000, 3), false);
        DeploymentSpec spec = new DeploymentSpec(1L, DeploymentStatus.BUILDING, ExecutionMode.REAL, 1, 1L, "app",
                repository.toUri().toString(), "main", ApplicationType.NODE_JS, null, "node server.js", 3000, null,
                null, null);

        assertThatThrownBy(() -> production.build(spec, new ExecutionContext(1L, mock(DeploymentLogWriter.class), 100)))
                .isInstanceOf(DeploymentStepException.class)
                .hasMessageContaining("transport 'file' not allowed");
    }

    private List<String> git(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=Test", "-c", "user.email=test@example.com",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        List<String> output = new ArrayList<>();
        CommandRunner.Result result = real.run(command, Map.of(), dir, Duration.ofSeconds(30), null, output::add);
        assertThat(result.ok()).as("git %s: %s", List.of(args), output).isTrue();
        return output;
    }

    private static void write(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
