package com.deployhub.platform.models.DTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EnvVarValueRequestDTO(
        @NotNull
        @Size(max = 2000)
        @Pattern(regexp = "^[^\\x00]*$", message = "must not contain NUL characters")
        String value) {

    @Override
    public String toString() {
        return "EnvVarValueRequestDTO[value=***]";
    }
}
