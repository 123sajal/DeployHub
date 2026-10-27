package com.deployhub.platform.models.DTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Which missing variables to fill in automatically; the server decides the values. */
public record EnvApplyRequestDTO(
        @NotNull
        @Size(max = 50)
        List<@Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]{0,99}$") String> names) {
}
