package com.deployhub.platform.execution;

import com.deployhub.platform.config.DeployHubProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real MySQL in Docker: a separate test server (own name, volume and port, so it never touches a real one),
 * two project databases, and proof that each project's user can only use its own. Run with
 * {@code ./mvnw test -Pdocker-it}; the first run pulls mysql:8.4.
 */
@Tag("docker")
class DockerMysqlServerDockerTest {

    private static final String ADMIN = "TestAdminPassword123";
    private static final String CONTAINER = "deployhub-mysql-test";
    private static final String VOLUME = "deployhub-mysql-test-data";

    private static final ProcessCommandRunner runner = new ProcessCommandRunner();
    private static DockerMysqlServer server;

    @BeforeAll
    static void startServer() {
        removeServer();
        server = new DockerMysqlServer(runner, new DeployHubProperties.Databases("mysql:8.4", CONTAINER, VOLUME, 3399,
                "512m", Duration.ofMinutes(4)), "docker");
        server.ensureRunning(ADMIN);
    }

    @AfterAll
    static void removeServer() {
        docker("rm", "--force", CONTAINER);
        docker("volume", "rm", "--force", VOLUME);
        if (server != null) {
            runner.shutdown();
        }
    }

    /** Runs SQL as a project's user, inside the server container (password via the environment). */
    private static Result asUser(String user, String password, String sql) {
        List<String> output = new ArrayList<>();
        CommandRunner.Result result = runner.run(List.of("docker", "exec", "-e", "MYSQL_PWD", CONTAINER, "mysql",
                        "--protocol=tcp", "-h127.0.0.1", "-u" + user, "-N", "-e", sql),
                Map.of("MYSQL_PWD", password), null, Duration.ofSeconds(60), null, output::add);
        return new Result(result.ok(), String.join("\n", output));
    }

    record Result(boolean ok, String output) {
    }

    @Test
    void eachProjectUserCanUseItsOwnDatabaseButNotAnotherOne() {
        server.createDatabase(ADMIN, "p1_shop", "p1_shop", "ShopPassword12345");
        server.createDatabase(ADMIN, "p2_blog", "p2_blog", "BlogPassword12345");

        assertThat(asUser("p1_shop", "ShopPassword12345",
                "CREATE TABLE p1_shop.items (id INT); INSERT INTO p1_shop.items VALUES (7); SELECT id FROM p1_shop.items")
                .output()).isEqualTo("7");

        Result intruder = asUser("p2_blog", "BlogPassword12345", "SELECT * FROM p1_shop.items");
        assertThat(intruder.ok()).isFalse();
        assertThat(intruder.output()).contains("denied");
        assertThat(asUser("p2_blog", "BlogPassword12345", "SHOW DATABASES").output())
                .contains("p2_blog").doesNotContain("p1_shop");
    }

    @Test
    void creatingAgainIsHarmlessAndAPasswordChangeTakesEffect() {
        server.createDatabase(ADMIN, "p3_api", "p3_api", "FirstPassword12345");
        server.createDatabase(ADMIN, "p3_api", "p3_api", "FirstPassword12345"); // a repeated request

        server.changePassword(ADMIN, "p3_api", "SecondPassword1234");

        assertThat(asUser("p3_api", "FirstPassword12345", "SELECT 1").ok()).isFalse();
        assertThat(asUser("p3_api", "SecondPassword1234", "SELECT 1").ok()).isTrue();
    }

    @Test
    void droppingRemovesTheDatabaseAndItsUser() {
        server.createDatabase(ADMIN, "p4_old", "p4_old", "OldPassword123456");

        server.dropDatabase(ADMIN, "p4_old", "p4_old");

        assertThat(asUser("p4_old", "OldPassword123456", "SELECT 1").ok()).isFalse();
        server.dropDatabase(ADMIN, "p4_old", "p4_old"); // again: still fine
    }

    private static void docker(String... args) {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(args));
        runner.run(command, Map.of(), null, Duration.ofSeconds(60), null, line -> { });
    }
}
