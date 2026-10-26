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
}
