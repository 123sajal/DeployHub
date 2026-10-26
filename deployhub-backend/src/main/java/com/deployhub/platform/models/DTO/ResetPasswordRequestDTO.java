package com.deployhub.platform.models.DTO;

import com.deployhub.platform.security.EmailNormalizer;
import com.deployhub.platform.utils.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequestDTO(
        @NotBlank
        @Size(max = 254)
        String email,

        @NotBlank
        @Pattern(regexp = "^\\d{6}$", message = "must be the 6-digit code from the email")
        String code,

        @ValidPassword
        String newPassword) {

    public ResetPasswordRequestDTO {
        email = EmailNormalizer.normalize(email);
        code = code == null ? null : code.strip();
    }

    @Override
    public String toString() {
        return "ResetPasswordRequestDTO[email=" + email + ", code=***, newPassword=***]";
    }
}
