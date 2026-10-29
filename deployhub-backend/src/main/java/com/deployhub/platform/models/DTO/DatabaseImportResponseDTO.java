package com.deployhub.platform.models.DTO;

/**
 * @param tablesBefore tables the database had before the import
 * @param tablesAfter  tables it has now
 * @param message      what happened, in plain words (including what DeployHub removed from the file)
 */
public record DatabaseImportResponseDTO(int tablesBefore, int tablesAfter, String message) {
}
