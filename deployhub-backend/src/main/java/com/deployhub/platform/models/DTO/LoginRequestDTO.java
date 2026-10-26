package com.deployhub.platform.models.DTO;

import com.deployhub.platform.security.EmailNormalizer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LoginRequestDTO(
        @NotBlank
        @Size(max = 254)
        String email,

        @NotNull
        @Size(min = 1, max = 64)
        String password) {

    public LoginRequestDTO {
        email = EmailNormalizer.normalize(email);
    }

    @Override
    public String toString() {
        return "LoginRequestDTO[email=" + email + ", password=***]";
    }
}
