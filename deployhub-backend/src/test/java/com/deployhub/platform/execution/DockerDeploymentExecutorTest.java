package com.deployhub.platform.execution;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.deployment.DeploymentLogWriter;
import com.deployhub.platform.models.enums.ApplicationType;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;
import com.deployhub.platform.worker.DeploymentStepException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.invocation.Invocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/** Every git/docker command line and failure path, with a fake command runner (no Docker needed). */
class DockerDeploymentExecutorTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @TempDir Path workspace;

    private FakeRunner runner;
    private DeploymentLogWriter logs;
    private ExecutionContext context;
    private int probeStatus;

    /** Records commands; scripted rules decide what each one prints and returns. */
    static final class FakeRunner implements CommandRunner {

        record Call(List<String> command, Map<String, String> env) {
        }

        record Rule(Predicate<List<String>> matches, int exitCode, List<String> output, Consumer<List<String>> effect) {
        }

        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        final List<Rule> rules = new ArrayList<>();

        FakeRunner on(Predicate<List<String>> matches, int exitCode, String... output) {
            rules.addFirst(new Rule(matches, exitCode, List.of(output), command -> { }));
            return this;
        }

        FakeRunner onWithEffect(Predicate<List<String>> matches, Consumer<List<String>> effect) {
            rules.addFirst(new Rule(matches, 0, List.of(), effect));
            return this;
        }

        @Override
        public Result run(List<String> command, Map<String, String> env, Path workingDir, Duration timeout,
                          ExecutionContext context, Consumer<String> onLine) {
            calls.add(new Call(command, env));
            Rule rule = rules.stream().filter(r -> r.matches().test(command)).findFirst()
                    .orElse(new Rule(c -> true, 0, List.of(), c -> { }));
            rule.effect().accept(command);
            rule.output().forEach(onLine);
            return new Result(rule.exitCode(), false, rule.output());
        }

        List<List<String>> commandsContaining(String word) {
            return calls.stream().map(Call::command).filter(c -> c.contains(word)).toList();
        }
    }

    static Predicate<List<String>> has(String... words) {
        return command -> command.containsAll(List.of(words));
    }

    @BeforeEach
    void setUp() {
        runner = new FakeRunner().on(has("rev-parse"), 0, SHA).on(has("image", "inspect"), 1, "No such image");
        logs = mock(DeploymentLogWriter.class);
        context = new ExecutionContext(42L, logs, 1000);
        probeStatus = 200;
    }

    private DockerDeploymentExecutor executor(Duration startTimeout) {
        DeployHubProperties.Execution settings = new DeployHubProperties.Execution(ExecutionMode.REAL, "docker", "git",
                workspace.toString(), Duration.ofSeconds(10), Duration.ofMinutes(1), startTimeout, 20000, 20002,
                "512m", "1.0", 256, 1000, 3);
        return new DockerDeploymentExecutor(runner, (port, path) -> probeStatus, settings, false);
    }

    private DockerDeploymentExecutor executor() {
        return executor(Duration.ofSeconds(5));
    }

    private static DeploymentSpec spec(ApplicationType type, String ref, String startCommand, String imageRef) {
        return new DeploymentSpec(42L, DeploymentStatus.BUILDING, ExecutionMode.REAL, 1, 7L, "shop",
                "https://github.com/acme/shop", ref, type, "npm ci", startCommand, 3000, "/health", null, imageRef);
    }

    private List<String> loggedMessages() {
        List<String> messages = new ArrayList<>();
        for (Invocation invocation : mockingDetails(logs).getInvocations()) {
            messages.add(String.valueOf(invocation.getArguments()[1]));
        }
        return messages;
    }

    // ---------------------------------------------------------------- build

    @Test
    void fetchesReadOnlyWithEveryCredentialSourceOffThenBuildsATaggedImage() {
        AtomicReference<String> dockerfile = new AtomicReference<>();
        runner.onWithEffect(has("build", "--progress=plain"), command -> dockerfile.set(read(
                Path.of(command.get(command.indexOf("-f") + 1)))));

        DeploymentExecutor.BuildResult result = executor().build(spec(ApplicationType.NODE_JS, "main", "npm start", null),
                context);

        assertThat(result.resolvedCommit()).isEqualTo(SHA);
        assertThat(result.imageRef()).matches("deployhub/p7:0123456789ab-[0-9a-f]{8}");
        List<String> fetch = runner.commandsContaining("fetch").getFirst();
        assertThat(fetch).containsSubsequence("git", "-c", "protocol.allow=never", "-c", "protocol.https.allow=always",
                "-c", "credential.helper=", "fetch", "--depth", "1", "--no-tags", "--end-of-options",
                "https://github.com/acme/shop", "main");
        assertThat(fetch).doesNotContain("push");
        Map<String, String> env = runner.calls.stream().filter(c -> c.command().contains("fetch")).findFirst()
                .orElseThrow().env();
        assertThat(env).containsEntry("GIT_TERMINAL_PROMPT", "0").containsEntry("GIT_CONFIG_NOSYSTEM", "1")
                .containsEntry("GIT_CONFIG_GLOBAL", "/dev/null");
        List<String> build = runner.commandsContaining("--progress=plain").getFirst();
        assertThat(build).containsSubsequence("docker", "build", "-t", result.imageRef(), "-f");
        assertThat(dockerfile.get()).contains("FROM node:22-alpine").contains("RUN npm ci")
                .contains("CMD [\"sh\",\"-c\",\"npm start\"]");
        assertThat(loggedMessages()).anyMatch(m -> m.contains("Fetched commit " + SHA))
                .anyMatch(m -> m.contains("Image built"));
        try (var left = Files.list(workspace)) {
            assertThat(left).as("source checkout and generated Dockerfile are deleted").isEmpty();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void reusesAnImageAlreadyBuiltFromTheSameCommitAndSettings() {
        runner.on(has("image", "inspect"), 0, "sha256:abc");

        DeploymentExecutor.BuildResult result = executor().build(spec(ApplicationType.NODE_JS, "main", "npm start", null),
                context);

        assertThat(runner.commandsContaining("--progress=plain")).isEmpty();
        assertThat(result.imageRef()).startsWith("deployhub/p7:0123456789ab-");
        assertThat(loggedMessages()).anyMatch(m -> m.startsWith("Reusing image"));
    }

    @Test
    void sameCommitWithDifferentSettingsGetsADifferentImage() {
        String a = DockerDeploymentExecutor.imageRef(spec(ApplicationType.NODE_JS, "main", "npm start", null), SHA);
        String b = DockerDeploymentExecutor.imageRef(spec(ApplicationType.NODE_JS, "main", "node server.js", null), SHA);
        assertThat(a).isNotEqualTo(b);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "remote: Repository not found.                                  | false | not public",
            "fatal: could not read Username for 'https://github.com'        | false | not public",
            "fatal: couldn't find remote ref refs/heads/nope                | false | was not found",
            "fatal: unable to access: Could not resolve host: github.com    | true  | Could not fetch the repository"})
    void gitFailuresAreClassified(String output, boolean retryable, String message) {
        runner.on(has("fetch"), 128, output);

        assertThatThrownBy(() -> executor().build(spec(ApplicationType.NODE_JS, "main", "npm start", null), context))
                .isInstanceOfSatisfying(DeploymentStepException.class, e -> {
                    assertThat(e.isRetryable()).isEqualTo(retryable);
                    assertThat(e.getMessage()).contains(message);
                });
    }

    @Test
    void aRefThatLooksLikeAnOptionIsRejectedBeforeRunningGit() {
        assertThatThrownBy(() -> executor().build(spec(ApplicationType.NODE_JS, "--upload-pack=x", "npm start", null),
                context))
                .isInstanceOf(DeploymentStepException.class).hasMessageContaining("Invalid branch or commit");
        assertThat(runner.calls).isEmpty();
    }

    @Test
    void dockerfileProjectNeedsADockerfile() {
        assertThatThrownBy(() -> executor().build(spec(ApplicationType.DOCKERFILE, "main", null, null), context))
                .isInstanceOfSatisfying(DeploymentStepException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).contains("No Dockerfile");
                });
    }

    @Test
    void dockerfileProjectIsBuiltFromTheRepositoryDockerfile() {
        runner.onWithEffect(has("checkout"), command -> write(
                Path.of(command.get(command.indexOf("-C") + 1)).resolve("Dockerfile"), "FROM nginx:alpine\n"));

        executor().build(spec(ApplicationType.DOCKERFILE, "main", null, null), context);

        List<String> build = runner.commandsContaining("--progress=plain").getFirst();
        assertThat(build.get(build.indexOf("-f") + 1)).endsWith("/Dockerfile");
    }

    @Test
    void dockerNotRunningIsRetried() {
        runner.on(has("--progress=plain"), 1, "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. "
                + "Is the docker daemon running?");

        assertThatThrownBy(() -> executor().build(spec(ApplicationType.NODE_JS, "main", "npm start", null), context))
                .isInstanceOfSatisfying(DeploymentStepException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getMessage()).contains("Docker is not running");
                });
    }

    @Test
    void aFailingBuildCommandFailsTheDeploymentWithItsLastOutputLine() {
        runner.on(has("--progress=plain"), 1, "#7 [4/4] RUN npm ci", "npm ERR! missing script: build", "");

        assertThatThrownBy(() -> executor().build(spec(ApplicationType.NODE_JS, "main", "npm start", null), context))
                .isInstanceOfSatisfying(DeploymentStepException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).isEqualTo("The build failed (exit code 1): npm ERR! missing script: build");
                });
    }

    @Test
    void aBuildFailureReportsTheRealErrorNotDockersSummaryOrDesktopLink() {
        runner.on(has("--progress=plain"), 1,
                "#10 [5/5] RUN cd web && npm ci && npx ng build",
                "#10 1.270 npm error `npm ci` can only install packages when your package.json and package-lock.json "
                        + "are in sync.",
                "#10 1.276 npm error Missing: @emnapi/runtime@1.11.3 from lock file",
                "#10 1.276 npm error Run \"npm help ci\" for more info",
                "#10 ERROR: process \"/bin/sh -c cd web && npm ci\" did not complete successfully: exit code: 1",
                "ERROR: failed to build: failed to solve: process \"/bin/sh -c cd web && npm ci\" did not complete",
                "View build details: docker-desktop://dashboard/build/desktop-linux/desktop-linux/abc123");

        assertThatThrownBy(() -> executor().build(spec(ApplicationType.NODE_JS, "main", "npm start", null), context))
                .isInstanceOf(DeploymentStepException.class)
                .hasMessage("The build failed (exit code 1): npm error `npm ci` can only install packages when your "
                        + "package.json and package-lock.json are in sync.");
    }

    // ---------------------------------------------------------------- start

    @Test
    void startsAHardenedContainerReachableOnlyFromThisMachine() {
        DeploymentExecutor.RunResult run = executor().start(spec(ApplicationType.NODE_JS, "main", "npm start",
                "deployhub/p7:abc"), context);

        assertThat(run.containerName()).isEqualTo("deployhub-d42");
        assertThat(run.hostPort()).isBetween(20000, 20002);
        assertThat(runner.calls.getFirst().command()).containsExactly("docker", "rm", "--force", "deployhub-d42");
        List<String> docker = runner.commandsContaining("--detach").getFirst();
        assertThat(docker).containsSubsequence("--memory", "512m").containsSubsequence("--cpus", "1.0")
                .containsSubsequence("--pids-limit", "256").containsSubsequence("--cap-drop", "ALL")
                .containsSubsequence("--security-opt", "no-new-privileges").containsSubsequence("--user", "10001:10001")
                .containsSubsequence("--publish", "127.0.0.1:" + run.hostPort() + ":3000")
                .containsSubsequence("-e", "PORT=3000");
        assertThat(docker.getLast()).isEqualTo("deployhub/p7:abc");
        assertThat(docker).containsSubsequence("--restart", "no");
        assertThat(runner.commandsContaining("update").getFirst())
                .containsExactly("docker", "update", "--restart", "unless-stopped", "deployhub-d42");
        assertThat(loggedMessages()).anyMatch(m -> m.contains("Health check passed: HTTP 200 on /health"));
    }

    @Test
    void environmentVariablesArePassedByNameSoValuesNeverAppearOnTheCommandLine() {
        DeploymentSpec base = spec(ApplicationType.NODE_JS, "main", "npm start", "deployhub/p7:abc");
        DeploymentSpec withEnv = new DeploymentSpec(base.deploymentId(), base.status(), base.mode(), base.attempt(),
                base.projectId(), base.projectName(), base.repositoryUrl(), base.ref(), base.appType(),
                base.buildCommand(), base.startCommand(), base.appPort(), base.healthCheckPath(), base.resolvedCommit(),
                base.imageRef(), Map.of("DB_PASSWORD", "hunter2-db-pass", "DB_HOST", "host.docker.internal"));

        executor().start(withEnv, context);

        FakeRunner.Call run = runner.calls.stream().filter(c -> c.command().contains("--detach")).findFirst()
                .orElseThrow();
        assertThat(run.command()).containsSubsequence("-e", "DB_HOST", "-e", "DB_PASSWORD")
                .containsSubsequence("--network", "deployhub-net")
                .noneMatch(arg -> arg.contains("hunter2") || arg.contains("host.docker.internal"));
        assertThat(run.env()).containsEntry("DB_PASSWORD", "hunter2-db-pass")
                .containsEntry("DB_HOST", "host.docker.internal");
        assertThat(withEnv.toString()).doesNotContain("hunter2").contains("DB_PASSWORD");
    }

    @Test
    void publishingCreatesConfiguresAndStartsTheAddressProxyOnFirstUse() {
        runner.on(has("container", "inspect"), 1, "No such container");
        AtomicReference<String> conf = new AtomicReference<>();
        runner.onWithEffect(has("cp"), command -> conf.set(read(Path.of(command.get(2)))));

        executor().publish(new DeploymentExecutor.Gateway(7L, "shop", 19001, "deployhub-d42", 3000, List.of()));

        List<String> create = runner.commandsContaining("create").getLast();
        assertThat(create).containsSubsequence("--name", "deployhub-edge-p7")
                .containsSubsequence("--network", "deployhub-net")
                .containsSubsequence("--publish", "127.0.0.1:19001:8080")
                .containsSubsequence("--cap-drop", "ALL").containsSubsequence("--restart", "unless-stopped");
        assertThat(create.getLast()).isEqualTo("nginx:1.27-alpine");
        assertThat(runner.commandsContaining("cp").getFirst().getLast())
                .isEqualTo("deployhub-edge-p7:/etc/nginx/conf.d/default.conf");
        assertThat(runner.commandsContaining("start").getFirst()).containsExactly("docker", "start", "deployhub-edge-p7");
        assertThat(conf.get()).contains("http://deployhub-d42:3000");
    }

    @Test
    void publishingAgainRewritesTheConfigAndReloadsWithoutDowntime() {
        runner.on(has("container", "inspect"), 0, "true");

        executor().publish(new DeploymentExecutor.Gateway(7L, "shop", 19001, "deployhub-d43", 3000, List.of()));

        assertThat(runner.commandsContaining("create")).isEmpty();
        assertThat(runner.commandsContaining("reload").getFirst())
                .containsExactly("docker", "exec", "deployhub-edge-p7", "nginx", "-s", "reload");
    }

    @Test
    void aPublicLinkIsATunnelToTheProjectsAddressWithNothingOpenedOnThisMachine() {
        int[] inspections = {0};
        // Not running at first; after "docker run" it is, and its log shows the link.
        runner.on(has("container", "inspect", "deployhub-tunnel-p7"), 0, "true");
        runner.rules.addFirst(new FakeRunner.Rule(has("container", "inspect", "deployhub-tunnel-p7")
                .and(c -> inspections[0]++ == 0), 1, List.of("No such container"), c -> { })); // checked first
        runner.on(has("logs", "deployhub-tunnel-p7"), 0, "INF Registered tunnel connection",
                "INF |  https://brave-ocean-tiger.trycloudflare.com  |");

        String link = executor().startPublicLink(7L);

        assertThat(link).isEqualTo("https://brave-ocean-tiger.trycloudflare.com");
        List<String> run = runner.commandsContaining("--detach").getFirst();
        assertThat(run).containsSubsequence("--name", "deployhub-tunnel-p7")
                .containsSubsequence("--network", "deployhub-net")
                .containsSubsequence("--cap-drop", "ALL")
                .containsSubsequence("--restart", "unless-stopped")
                .containsSubsequence("cloudflare/cloudflared:latest", "tunnel", "--no-autoupdate", "--url",
                        "http://deployhub-edge-p7:8080")
                .doesNotContain("--publish");
    }

    @Test
    void aRunningTunnelIsAdoptedAndItsNewestLinkWins() {
        runner.on(has("container", "inspect", "deployhub-tunnel-p7"), 0, "true");
        runner.on(has("logs", "deployhub-tunnel-p7"), 0, "https://old-link-one.trycloudflare.com",
                "restarted", "https://new-link-two.trycloudflare.com");

        assertThat(executor().startPublicLink(7L)).isEqualTo("https://new-link-two.trycloudflare.com");
        assertThat(runner.commandsContaining("--detach")).as("adopted, not started again").isEmpty();
    }

    @Test
    void aStoppedTunnelHasNoLinkAndStoppingRemovesIt() {
        runner.on(has("container", "inspect", "deployhub-tunnel-p7"), 0, "false");
        assertThat(executor().currentPublicLink(7L)).isNull();

        executor().stopPublicLink(7L);
        assertThat(runner.calls.getLast().command()).containsExactly("docker", "rm", "--force", "deployhub-tunnel-p7");
    }

    @Test
    void unpublishingRemovesTheProxy() {
        executor().unpublish(7L);
        assertThat(runner.calls.getLast().command()).containsExactly("docker", "rm", "--force", "deployhub-edge-p7");
    }

    @Test
    void dockerfileProjectsKeepTheirImageUserButCanOverrideTheStartCommand() {
        executor().start(spec(ApplicationType.DOCKERFILE, "main", "node server.js", "deployhub/p7:abc"), context);

        List<String> docker = runner.commandsContaining("--detach").getFirst();
        assertThat(docker).doesNotContain("--user").containsSubsequence("--cap-add", "NET_BIND_SERVICE")
                .containsSubsequence("--entrypoint", "sh", "deployhub/p7:abc", "-c", "node server.js");
    }

    @Test
    void triesTheNextPortWhenOneIsTaken() {
        int[] runs = {0};
        runner.rules.addFirst(new FakeRunner.Rule(has("--detach").and(c -> runs[0]++ == 0), 125,
                List.of("Bind for 127.0.0.1:20000 failed: port is already allocated"), c -> { }));

        DeploymentExecutor.RunResult run = executor().start(spec(ApplicationType.NODE_JS, "main", "npm start",
                "deployhub/p7:abc"), context);

        List<List<String>> attempts = runner.commandsContaining("--detach");
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0)).isNotEqualTo(attempts.get(1));
        assertThat(run.hostPort()).isNotNull();
    }

    @Test
    void anAppThatExitsFailsWithItsOutputAndIsRemoved() {
        probeStatus = -1;
        runner.on(has("inspect", "--format"), 0, "exited 0 1").on(has("logs"), 0, "Error: Cannot find module 'express'");

        assertThatThrownBy(() -> executor().start(spec(ApplicationType.NODE_JS, "main", "npm start",
                "deployhub/p7:abc"), context))
                .isInstanceOfSatisfying(DeploymentStepException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).contains("The app stopped (exit code 1)");
                });
        assertThat(loggedMessages()).anyMatch(m -> m.contains("Cannot find module 'express'"));
        assertThat(runner.calls.getLast().command()).containsExactly("docker", "rm", "--force", "deployhub-d42");
    }

    @Test
    void anAppThatNeverAnswersTimesOutAndIsRemoved() {
        probeStatus = -1;
        runner.on(has("inspect", "--format"), 0, "running 0 0");

        assertThatThrownBy(() -> executor(Duration.ofMillis(200)).start(spec(ApplicationType.NODE_JS, "main",
                "npm start", "deployhub/p7:abc"), context))
                .isInstanceOf(DeploymentStepException.class)
                .hasMessageContaining("did not answer on port 3000").hasMessageContaining("0.0.0.0");
        assertThat(runner.calls.getLast().command()).containsExactly("docker", "rm", "--force", "deployhub-d42");
    }

    @Test
    void aCancelDuringTheHealthCheckRemovesTheContainer() {
        probeStatus = -1;
        runner.on(has("inspect", "--format"), 0, "running 0 0");
        context.cancel(ExecutionContext.CancelReason.CANCELLED_BY_USER);

        assertThatThrownBy(() -> executor().start(spec(ApplicationType.NODE_JS, "main", "npm start",
                "deployhub/p7:abc"), context))
                .isInstanceOf(StepCancelledException.class);
        assertThat(runner.calls.getLast().command()).containsExactly("docker", "rm", "--force", "deployhub-d42");
    }

    @Test
    void pruningKeepsTheGivenImages() {
        runner.on(has("image", "ls"), 0, "deployhub/p7:aaa", "deployhub/p7:bbb", "deployhub/p7:ccc");

        executor().pruneImages(7L, java.util.Set.of("deployhub/p7:bbb"));

        assertThat(runner.commandsContaining("rm").stream().map(List::getLast).toList())
                .containsExactlyInAnyOrder("deployhub/p7:aaa", "deployhub/p7:ccc");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.writeString(Objects.requireNonNull(path), content);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
