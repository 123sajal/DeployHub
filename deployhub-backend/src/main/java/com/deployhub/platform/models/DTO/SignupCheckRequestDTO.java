package com.deployhub.platform.models.DTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One answer from the sign-up assistant, checked before moving on. Passwords are never sent here. */
public record SignupCheckRequestDTO(
        @NotNull
        Field field,

        @Size(max = 300)
        String value) {

    public enum Field { NAME, EMAIL }
}
