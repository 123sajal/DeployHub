package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.Occupation;
import jakarta.validation.constraints.NotNull;

public record ProfileUpdateRequestDTO(@NotNull Occupation occupation) {
}
