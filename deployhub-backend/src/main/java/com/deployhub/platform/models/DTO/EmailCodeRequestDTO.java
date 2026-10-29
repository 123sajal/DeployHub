package com.deployhub.platform.models.DTO;

import com.deployhub.platform.security.EmailNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Asks for a 6-digit code sent to this address (sign-up email, or a college email for the student check). */
public record EmailCodeRequestDTO(
        @NotBlank
        @Size(max = 254)
        @Email
        @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "must be a valid email address")
        String email) {

    public EmailCodeRequestDTO {
        email = EmailNormalizer.normalize(email);
    }
}
