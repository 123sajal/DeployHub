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
        String rootDirectory,

        /** For a private repository: an access token typed into the form (not saved by detecting). */
        @Size(max = 500)
        @Pattern(regexp = "^\\s*[A-Za-z0-9_.~+/=:-]*\\s*$", message = "is not a valid access token")
        String repositoryToken,

        /** When editing a project: use its saved access token if none is typed. */
        Long projectId) {

    public DetectRequestDTO(String repositoryUrl, String branch, String rootDirectory) {
        this(repositoryUrl, branch, rootDirectory, null, null);
    }

    @Override
    public String toString() {
        return "DetectRequestDTO[repositoryUrl=" + repositoryUrl + ", branch=" + branch + ", rootDirectory="
                + rootDirectory + ", repositoryToken=" + (repositoryToken == null ? "none" : "***") + "]";
    }

    public DetectRequestDTO {
        repositoryUrl = repositoryUrl == null ? null : repositoryUrl.trim();
        branch = branch == null ? null : branch.trim();
        repositoryToken = repositoryToken == null || repositoryToken.isBlank() ? null : repositoryToken.trim();
    }
}
