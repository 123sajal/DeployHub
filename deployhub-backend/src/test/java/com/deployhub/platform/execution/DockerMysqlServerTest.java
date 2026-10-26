package com.deployhub.platform.execution;

import com.deployhub.platform.config.DeployHubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.deployhub.platform.execution.DockerDeploymentExecutorTest.has;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Command lines of the shared MySQL server, with a fake command runner (no Docker needed). */
class DockerMysqlServerTest {

    private static final String ADMIN = "AdminPassword123456";

    private DockerDeploymentExecutorTest.FakeRunner runner;
    private DockerMysqlServer server;

    @BeforeEach
    void setUp() {
        runner = new DockerDeploymentExecutorTest.FakeRunner();
        server = new DockerMysqlServer(runner, new DeployHubProperties.Databases("mysql:8.4", "deployhub-mysql",
                "deployhub-mysql-data", 3307, "512m", Duration.ofSeconds(10)), "docker");
    }

    @Test
    void theFirstUseCreatesTheServerWithItsDataInAVolumeAndThePasswordKeptOffTheCommandLine() {
        runner.on(has("container", "inspect"), 1, "No such container");

        server.ensureRunning(ADMIN);

        DockerDeploymentExecutorTest.FakeRunner.Call run = runner.calls.stream()
                .filter(c -> c.command().contains("--detach")).findFirst().orElseThrow();
        assertThat(run.command()).containsSubsequence("--name", "deployhub-mysql")
                .containsSubsequence("--network", "deployhub-net")
                .containsSubsequence("--publish", "127.0.0.1:3307:3306")
                .containsSubsequence("--volume", "deployhub-mysql-data:/var/lib/mysql")
                .containsSubsequence("--memory", "512m")
                .containsSubsequence("-e", "MYSQL_ROOT_PASSWORD")
                .noneMatch(arg -> arg.contains(ADMIN));
        assertThat(run.env()).containsEntry("MYSQL_ROOT_PASSWORD", ADMIN);
    }

    @Test
    void waitsUntilTheServerReallyAcceptsConnections() {
        runner.on(has("container", "inspect"), 0, "true");
        AtomicInteger attempts = new AtomicInteger();
        runner.rules.addFirst(new DockerDeploymentExecutorTest.FakeRunner.Rule(
                has("exec").and(c -> attempts.incrementAndGet() < 3), 1, List.of("Can't connect to MySQL server"),
                c -> { }));

        server.ensureRunning(ADMIN);

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(runner.commandsContaining("--detach")).as("already exists: not created again").isEmpty();
    }

    @Test
    void aStoppedServerIsStarted() {
        runner.on(has("container", "inspect"), 0, "false");

        server.ensureRunning(ADMIN);

        assertThat(runner.commandsContaining("start").getFirst()).containsExactly("docker", "start", "deployhub-mysql");
    }

    @Test
    void eachDatabaseGetsAUserThatCanOnlyUseIt() {
        server.createDatabase(ADMIN, "p7_shop", "p7_shop", "UserPassword123456");

        DockerDeploymentExecutorTest.FakeRunner.Call exec = runner.calls.getLast();
        assertThat(exec.command()).containsSubsequence("exec", "-e", "MYSQL_PWD", "-e", "DEPLOYHUB_SQL", "deployhub-mysql")
                .noneMatch(arg -> arg.contains("UserPassword") || arg.contains(ADMIN));
        assertThat(exec.env()).containsEntry("MYSQL_PWD", ADMIN);
        assertThat(exec.env().get("DEPLOYHUB_SQL"))
                .contains("CREATE DATABASE IF NOT EXISTS `p7_shop`")
                .contains("CREATE USER IF NOT EXISTS 'p7_shop'@'%' IDENTIFIED BY 'UserPassword123456'")
                .contains("GRANT ALL PRIVILEGES ON `p7_shop`.* TO 'p7_shop'@'%'")
                .doesNotContain("*.*");
    }

    @Test
    void identifiersAndPasswordsThatCouldInjectSqlAreRefused() {
        assertThatThrownBy(() -> server.createDatabase(ADMIN, "x`; DROP DATABASE y; --", "u", "UserPassword123456"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> server.createDatabase(ADMIN, "db", "db", "pass'word12345"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(runner.calls).isEmpty();
    }

    @Test
    void droppingRemovesTheDatabaseAndItsUser() {
        server.dropDatabase(ADMIN, "p7_shop", "p7_shop");

        assertThat(runner.calls.getLast().env().get("DEPLOYHUB_SQL"))
                .isEqualTo("DROP DATABASE IF EXISTS `p7_shop`; DROP USER IF EXISTS 'p7_shop'@'%';");
    }

    @Test
    void dockerNotRunningIsExplained() {
        runner.on(has("exec"), 1, "Cannot connect to the Docker daemon at unix:///var/run/docker.sock.");

        assertThatThrownBy(() -> server.createDatabase(ADMIN, "p7_shop", "p7_shop", "UserPassword123456"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Docker is not running");
    }
}
