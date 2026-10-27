package com.deployhub.platform.models.DTO;

import com.deployhub.platform.utils.ValidRepositoryUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Where to look: the same repository, branch and App folder rules as a project. */
public record DetectRequestDTO(
        @NotBlank
        @ValidRepositoryUrl
        String repositoryUrl,

        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^(?!.*\\.\\.)[A-Za-z0-9._/-]+$", message = "is not a valid branch name")
        String branch,

        @Size(max = 200)
        @Pattern(regexp = "^\\s*(\\./)?[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*/?\\s*$|^\\s*$",
                message = "must be a folder inside the repository, e.g. backend or apps/web (letters, digits, . _ - and /)")
        String rootDirectory) {

    public DetectRequestDTO {
        repositoryUrl = repositoryUrl == null ? null : repositoryUrl.trim();
        branch = branch == null ? null : branch.trim();
    }
}
