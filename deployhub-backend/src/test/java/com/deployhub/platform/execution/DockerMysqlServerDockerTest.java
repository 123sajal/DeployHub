package com.deployhub.platform.execution;

import com.deployhub.platform.config.DeployHubProperties;
import com.deployhub.platform.utils.SqlDumpCleaner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** A file like `mysqldump --databases localdb` from someone's laptop: database switch, DEFINERs, server settings. */
    private static final String LAPTOP_EXPORT = """
            -- MySQL dump 10.13
            /*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
            SET @@SESSION.SQL_LOG_BIN= 0;
            CREATE DATABASE /*!32312 IF NOT EXISTS*/ `localdb` /*!40100 DEFAULT CHARACTER SET utf8mb4 */;
            USE `localdb`;
            DROP TABLE IF EXISTS `employees`;
            CREATE TABLE `employees` (`id` int NOT NULL AUTO_INCREMENT, `name` varchar(50), PRIMARY KEY (`id`));
            INSERT INTO `employees` VALUES (1,'Asha'),(2,'Ravi');
            /*!50001 CREATE ALGORITHM=UNDEFINED */
            /*!50013 DEFINER=`root`@`localhost` SQL SECURITY DEFINER */
            /*!50001 VIEW `employee_names` AS select `employees`.`name` AS `name` from `employees` */;
            """;

    private static Path cleaned(String sql) throws Exception {
        Path file = Files.createTempFile("import-test-", ".sql");
        try (var out = Files.newOutputStream(file)) {
            SqlDumpCleaner.clean(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)), out, 1_000_000);
        }
        return file;
    }

    @Test
    void aLaptopExportImportsIntoTheProjectsDatabaseAndExportsBackOut() throws Exception {
        server.createDatabase(ADMIN, "p5_shop", "p5_shop", "ShopPassword12345");

        server.importSql(ADMIN, "p5_shop", "p5_shop", "ShopPassword12345", cleaned(LAPTOP_EXPORT), false);

        assertThat(server.countTables(ADMIN, "p5_shop")).as("table + view").isEqualTo(2);
        // The view was created by the project's own user, so it keeps working (its DEFINER=root was removed).
        assertThat(asUser("p5_shop", "ShopPassword12345", "SELECT name FROM p5_shop.employee_names ORDER BY name")
                .output()).isEqualTo("Asha\nRavi");

        Path export = Files.createTempFile("export-test-", ".sql");
        server.exportSql("p5_shop", "p5_shop", "ShopPassword12345", export);
        String dump = Files.readString(export);
        assertThat(dump).contains("CREATE TABLE `employees`").contains("'Asha'").contains("employee_names");

        server.createDatabase(ADMIN, "p6_copy", "p6_copy", "CopyPassword12345");
        server.importSql(ADMIN, "p6_copy", "p6_copy", "CopyPassword12345", cleaned(dump), true);
        assertThat(asUser("p6_copy", "CopyPassword12345", "SELECT COUNT(*) FROM p6_copy.employees").output()).isEqualTo("2");
    }

    @Test
    void replacingEmptiesTheDatabaseFirstAndTheProjectUserKeepsItsRights() throws Exception {
        server.createDatabase(ADMIN, "p7_app", "p7_app", "AppPassword123456");
        asUser("p7_app", "AppPassword123456", "CREATE TABLE p7_app.old_stuff (id INT)");

        server.importSql(ADMIN, "p7_app", "p7_app", "AppPassword123456", cleaned("CREATE TABLE fresh (id INT);"), true);

        assertThat(asUser("p7_app", "AppPassword123456", "SHOW TABLES FROM p7_app").output()).isEqualTo("fresh");
        assertThat(asUser("p7_app", "AppPassword123456", "INSERT INTO p7_app.fresh VALUES (1)").ok()).isTrue();
    }

    @Test
    void aFileCantTouchAnotherDatabaseAndBrokenSqlSaysWhy() throws Exception {
        server.createDatabase(ADMIN, "p8_mine", "p8_mine", "MinePassword12345");
        server.createDatabase(ADMIN, "p9_theirs", "p9_theirs", "TheirPassword1234");
        asUser("p9_theirs", "TheirPassword1234", "CREATE TABLE p9_theirs.secrets (v VARCHAR(10)); INSERT INTO p9_theirs.secrets VALUES ('keep')");

        assertThatThrownBy(() -> server.importSql(ADMIN, "p8_mine", "p8_mine", "MinePassword12345",
                cleaned("DROP TABLE p9_theirs.secrets;"), false))
                .isInstanceOf(ManagedDatabaseServer.SqlFailed.class)
                .hasMessageContaining("denied");
        assertThat(asUser("p9_theirs", "TheirPassword1234", "SELECT v FROM p9_theirs.secrets").output()).isEqualTo("keep");

        assertThatThrownBy(() -> server.importSql(ADMIN, "p8_mine", "p8_mine", "MinePassword12345",
                cleaned("CREATE TABEL oops (id INT);"), false))
                .isInstanceOf(ManagedDatabaseServer.SqlFailed.class)
                .hasMessageContaining("syntax");

        assertThatThrownBy(() -> server.importSql(ADMIN, "p8_mine", "p8_mine", "WrongPassword1234",
                cleaned("CREATE TABLE t (id INT);"), false))
                .isInstanceOf(ManagedDatabaseServer.SqlFailed.class)
                .hasMessageContaining("ERROR 1045");
    }

    private static void docker(String... args) {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(args));
        runner.run(command, Map.of(), null, Duration.ofSeconds(60), null, line -> { });
    }
}
