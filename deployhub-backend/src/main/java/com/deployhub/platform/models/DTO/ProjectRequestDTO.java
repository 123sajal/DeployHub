package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.ApplicationType;
import com.deployhub.platform.utils.ValidRepositoryUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProjectRequestDTO(
        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]*[a-z0-9]$",
                message = "must use lowercase letters, digits and hyphens, and not start or end with a hyphen")
        String name,

        @NotBlank
        @ValidRepositoryUrl
        String repositoryUrl,

        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^(?!.*\\.\\.)[A-Za-z0-9._/-]+$", message = "is not a valid branch name")
        String branch,

        @NotNull
        ApplicationType appType,

        @Size(max = 500)
        String buildCommand,

        @NotBlank
        @Size(max = 500)
        String startCommand) {
}
