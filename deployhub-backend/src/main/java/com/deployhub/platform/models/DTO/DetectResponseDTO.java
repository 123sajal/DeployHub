package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.ApplicationType;

import java.util.List;

/**
 * What the repository looks like and the commands that fit it. Every field may be null when nothing was found.
 *
 * @param appFolders folders that look like apps when the chosen folder has none (e.g. backend, frontend)
 * @param notes      plain-language explanations of what was detected and anything the user still has to do
 */
public record DetectResponseDTO(ApplicationType appType, String buildCommand, String startCommand,
                                List<String> appFolders, List<String> notes) {
}
