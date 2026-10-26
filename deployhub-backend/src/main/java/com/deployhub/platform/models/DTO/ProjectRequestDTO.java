package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.ApplicationType;
import com.deployhub.platform.utils.ValidRepositoryUrl;
import com.deployhub.platform.utils.ValidStartCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@ValidStartCommand
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

        // Commands end up in a generated Dockerfile, where a line break would start a new instruction.
        @Size(max = 500)
        @Pattern(regexp = "^[^\\r\\n]*$", message = "must be a single line")
        String buildCommand,

        /** Required unless appType is DOCKERFILE (see @ValidStartCommand). */
        @Size(max = 500)
        @Pattern(regexp = "^[^\\r\\n]*$", message = "must be a single line")
        String startCommand,

        /** Port the app listens on inside its container; empty = the app type's default. */
        @Min(1)
        @Max(65535)
        Integer appPort,

        /** Path the health check requests; empty = "/". */
        @Size(max = 200)
        @Pattern(regexp = "^/[A-Za-z0-9._~/?=&%-]*$", message = "must start with / (for example /health)")
        String healthCheckPath) {
}
