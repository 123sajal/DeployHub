package com.deployhub.platform.models.DTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Replaces all routes of a project. An empty list removes them. */
public record ProjectRoutesRequestDTO(@NotNull @Size(max = 10) List<@Valid Route> routes) {

    /** Requests to {@code pathPrefix} (e.g. /api) on this project's address go to the target project's live app. */
    public record Route(
            @NotBlank
            @Size(max = 100)
            @Pattern(regexp = "^/[A-Za-z0-9._~/-]+$", message = "must start with / and name a path, for example /api")
            String pathPrefix,
            @NotNull
            Long targetProjectId) {
    }
}
