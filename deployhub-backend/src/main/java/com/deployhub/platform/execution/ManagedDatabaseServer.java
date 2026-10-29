package com.deployhub.platform.execution;

/**
 * The shared database server behind one-click databases. Every operation is idempotent, so a request that
 * failed halfway can simply be repeated.
 */
public interface ManagedDatabaseServer {

    /** Where apps reach the server (on the deployhub network) and where this machine reaches it. */
    record Endpoints(String appHost, int appPort, String localHost, int localPort) {
    }

    Endpoints endpoints();

    /** Starts the server if needed (creating it with {@code adminPassword} the first time) and waits until ready. */
    void ensureRunning(String adminPassword);

    /** Creates the database and a user that can only use it (or resets them if they exist). */
    void createDatabase(String adminPassword, String databaseName, String username, String password);

    void changePassword(String adminPassword, String username, String password);

    /** Deletes the database with all its data, and its user. */
    void dropDatabase(String adminPassword, String databaseName, String username);

    /** How many tables the database has. */
    int countTables(String adminPassword, String databaseName);

    /**
     * Runs a SQL file in the database as the project's own user, which can use only that database (so the file can't
     * touch anything else) and becomes the owner of the views and triggers it creates (so they keep working).
     *
     * @param replace empty the database first (as admin; the project's user keeps its rights)
     * @throws SqlFailed with MySQL's message when the file doesn't run
     */
    void importSql(String adminPassword, String databaseName, String username, String userPassword,
                   java.nio.file.Path sqlFile, boolean replace);

    /** Writes the whole database (tables, data, views, triggers) to {@code target} as a SQL file, as the project's user. */
    void exportSql(String databaseName, String username, String userPassword, java.nio.file.Path target);

    /** The SQL itself failed (a mistake in the file, not a broken server). */
    class SqlFailed extends RuntimeException {
        public SqlFailed(String message) {
            super(message);
        }
    }
}
