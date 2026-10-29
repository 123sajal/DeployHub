package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.DatabaseImportResponseDTO;
import com.deployhub.platform.models.DTO.DatabaseResponseDTO;

import java.util.Optional;

/** One-click databases: a project's own database on DeployHub's shared MySQL server. */
public interface IProjectDatabaseService {

    Optional<DatabaseResponseDTO> get(Long projectId);

    /** Creates the database and sets its connection details as environment variables. The password is returned once. */
    DatabaseResponseDTO create(Long projectId);

    /** Issues a new password (returned once) and updates the environment variables. */
    DatabaseResponseDTO resetPassword(Long projectId);

    /** Deletes the database with all its data, and its environment variables. */
    void remove(Long projectId);

    /** After a project was deleted: drop its database on the server (its rows are already gone). Best effort. */
    void dropAfterProjectDeleted(String databaseName, String username);

    /**
     * Loads a SQL file (an export from the user's own computer) into the project's database.
     *
     * @param replace delete the current data first; without it, a database that already has tables is refused
     */
    DatabaseImportResponseDTO importData(Long projectId, java.io.InputStream sqlFile, boolean replace);

    /** Writes the project's database to a temporary SQL file; the caller sends it and deletes it. */
    java.nio.file.Path exportData(Long projectId);

    /** The file name a download of the project's database gets, e.g. p305_shop-2026-10-28.sql. */
    String exportFileName(Long projectId);
}
