package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.EnvVarKind;

import java.util.List;

/**
 * The environment variables a project's app needs, read from its repository, and how far they are set up.
 *
 * @param databaseSuggested the app uses database variables and the project has no database yet
 * @param autoFillable      missing variables DeployHub can fill in by itself ("Fill in automatically")
 * @param notes             plain-language remarks (where the list came from, what couldn't be read)
 */
public record EnvRequirementsResponseDTO(List<Item> items, boolean databaseSuggested, boolean databasePresent,
                                         int missingRequired, List<String> autoFillable, List<String> notes) {

    /**
     * @param status         SET (has a value), PROVIDED (DeployHub sets it) or MISSING
     * @param required       false when the app has a default (e.g. process.env.X || 'y')
     * @param suggestedValue only for SETTING: the value Fill in automatically uses (never a secret)
     * @param source         where it was found, e.g. ".env.example" or "src/server.js"
     * @param help           what to do, in plain words
     */
    public record Item(String name, EnvVarKind kind, String status, boolean required, String suggestedValue,
                       String source, String help) {
    }
}
