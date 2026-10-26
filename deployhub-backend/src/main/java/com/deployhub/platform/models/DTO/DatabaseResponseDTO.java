package com.deployhub.platform.models.DTO;

import java.time.Instant;
import java.util.List;

/**
 * A project's one-click database.
 *
 * @param appHost              how the app reaches it (set as DB_HOST)
 * @param localHost            how this machine reaches it, e.g. to create tables with a script or a GUI
 * @param password             only right after creating it or resetting the password; null otherwise
 * @param environmentVariables the variables DeployHub set on the project for it
 */
public record DatabaseResponseDTO(String engine, String databaseName, String username, String appHost, int appPort,
                                  String localHost, int localPort, String password, Instant createdAt,
                                  List<String> environmentVariables) {

    @Override
    public String toString() {
        return "DatabaseResponseDTO[" + engine + " " + databaseName + ", password=" + (password == null ? "-" : "***")
                + "]";
    }
}
