package com.deployhub.platform.utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Makes a SQL export from someone's own computer (mysqldump, MySQL Workbench, phpMyAdmin) run in a DeployHub database,
 * where its user may only use that one database and has no admin rights. Removes what such a file typically contains
 * but can't do there:
 * <ul>
 *   <li>{@code CREATE DATABASE ...} and {@code USE ...}: the data goes into the project's database, whatever its
 *       name was on the computer it came from;</li>
 *   <li>{@code DEFINER=`root`@`localhost`} on views, triggers and routines (needs admin rights);</li>
 *   <li>{@code SET @@GLOBAL...} / {@code SET @@SESSION.SQL_LOG_BIN} / GTID lines (server settings).</li>
 * </ul>
 * Everything else is copied unchanged.
 */
public final class SqlDumpCleaner {

    /** What was changed, for the message shown after an import. */
    public record Result(long bytes, int removedStatements, int removedDefiners) {
    }

    private static final Pattern DATABASE_SWITCH = Pattern.compile(
            "(?i)^\\s*(CREATE\\s+(DATABASE|SCHEMA)\\b|USE\\s+[`\\w]).*$");
    private static final Pattern SERVER_SETTING = Pattern.compile(
            "(?i)^\\s*SET\\s+@@(GLOBAL\\.|SESSION\\.SQL_LOG_BIN|GLOBAL\\.GTID_PURGED).*$|^\\s*SET\\s+@MYSQLDUMP_TEMP_LOG_BIN.*$");
    private static final Pattern DEFINER = Pattern.compile("(?i)DEFINER\\s*=\\s*(`[^`]*`|'[^']*'|\\S+?)@(`[^`]*`|'[^']*'|[\\w.%-]+)");

    private SqlDumpCleaner() {
    }

    /**
     * Copies {@code in} to {@code out}, cleaned, line by line (so large files don't fill the memory).
     *
     * @throws TooLarge when more than maxBytes come in
     */
    public static Result clean(InputStream in, OutputStream out, long maxBytes) throws IOException {
        long bytes = 0;
        int removedStatements = 0;
        int removedDefiners = 0;
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes > maxBytes) {
                throw new TooLarge(maxBytes);
            }
            if (DATABASE_SWITCH.matcher(line).matches() || SERVER_SETTING.matcher(line).matches()) {
                removedStatements++;
                writer.write("-- removed by DeployHub: " + line.trim().replaceAll("\\s+", " "));
                writer.newLine();
                continue;
            }
            var definer = DEFINER.matcher(line);
            if (definer.find()) {
                removedDefiners++;
                line = definer.replaceAll("");
            }
            writer.write(line);
            writer.newLine();
        }
        writer.flush();
        return new Result(bytes, removedStatements, removedDefiners);
    }

    public static class TooLarge extends RuntimeException {
        public TooLarge(long maxBytes) {
            super("The file is larger than " + (maxBytes / (1024 * 1024)) + " MB.");
        }
    }
}
