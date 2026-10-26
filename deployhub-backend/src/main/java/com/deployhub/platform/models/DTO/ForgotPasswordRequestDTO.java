package com.deployhub.platform.models.DTO;

import com.deployhub.platform.security.EmailNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequestDTO(
        @NotBlank
        @Size(max = 254)
        @Email
        String email) {

    public ForgotPasswordRequestDTO {
        email = EmailNormalizer.normalize(email);
    }
}
