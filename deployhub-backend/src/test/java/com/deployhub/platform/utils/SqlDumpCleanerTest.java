package com.deployhub.platform.utils;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlDumpCleanerTest {

    private static String clean(String sql, long max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SqlDumpCleaner.clean(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)), out, max);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void removesWhatOnlyWorksOnTheComputerTheExportCameFrom() throws Exception {
        String cleaned = clean("""
                CREATE DATABASE /*!32312 IF NOT EXISTS*/ `localdb`;
                USE `localdb`;
                SET @@SESSION.SQL_LOG_BIN= 0;
                SET @@GLOBAL.GTID_PURGED='abc';
                CREATE TABLE `t` (`id` int);
                INSERT INTO `t` VALUES (1),(2);
                /*!50013 DEFINER=`root`@`localhost` SQL SECURITY DEFINER */
                CREATE DEFINER='admin'@'%' TRIGGER trg BEFORE INSERT ON t FOR EACH ROW SET NEW.id = NEW.id;
                """, 1_000_000);

        assertThat(cleaned).doesNotContain("\nCREATE DATABASE").doesNotContain("\nUSE `").doesNotContain("DEFINER=")
                .doesNotContain("\nSET @@")
                .contains("-- removed by DeployHub: USE `localdb`;")
                .contains("CREATE TABLE `t` (`id` int);").contains("INSERT INTO `t` VALUES (1),(2);")
                .contains("CREATE  TRIGGER trg").contains("SQL SECURITY DEFINER");
    }

    @Test
    void ordinaryContentIsCopiedUnchanged() throws Exception {
        String sql = "INSERT INTO notes VALUES ('use the force', 'create database later');\nSELECT 1;\n";
        assertThat(clean(sql, 1_000_000)).isEqualTo(sql);
    }

    @Test
    void stopsAtTheSizeLimit() {
        assertThatThrownBy(() -> clean("x".repeat(2_000), 1_000)).isInstanceOf(SqlDumpCleaner.TooLarge.class);
    }
}
