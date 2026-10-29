package com.deployhub.platform.models.DTO;

import com.deployhub.platform.security.EmailNormalizer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EmailCodeVerifyRequestDTO(
        @NotBlank
        @Size(max = 254)
        String email,

        @NotBlank
        @Pattern(regexp = "^\\d{6}$", message = "must be the 6-digit code from the email")
        String code) {

    public EmailCodeVerifyRequestDTO {
        email = EmailNormalizer.normalize(email);
        code = code == null ? null : code.strip();
    }

    @Override
    public String toString() {
        return "EmailCodeVerifyRequestDTO[email=" + email + ", code=***]";
    }
}
