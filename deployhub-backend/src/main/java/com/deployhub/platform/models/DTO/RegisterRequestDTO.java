package com.deployhub.platform.models.DTO;

import com.deployhub.platform.security.EmailNormalizer;
import com.deployhub.platform.utils.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequestDTO(
        @NotBlank
        @Size(max = 100)
        String displayName,

        @NotBlank
        @Size(max = 254)
        @Email
        @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "must be a valid email address")
        String email,

        @ValidPassword
        String password) {

    /** Normalise before validation runs, so " Alice@Example.COM " and "alice@example.com" are one account. */
    public RegisterRequestDTO {
        email = EmailNormalizer.normalize(email);
        displayName = displayName == null ? null : displayName.trim();
    }

    @Override
    public String toString() {
        return "RegisterRequestDTO[displayName=" + displayName + ", email=" + email + ", password=***]";
    }
}
