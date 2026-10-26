package com.deployhub.platform.utils;

import com.deployhub.platform.models.DTO.ProjectRequestDTO;
import com.deployhub.platform.models.enums.ApplicationType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class StartCommandValidator implements ConstraintValidator<ValidStartCommand, ProjectRequestDTO> {

    @Override
    public boolean isValid(ProjectRequestDTO request, ConstraintValidatorContext context) {
        if (request == null || request.appType() == ApplicationType.DOCKERFILE
                || (request.startCommand() != null && !request.startCommand().isBlank())) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("startCommand")
                .addConstraintViolation();
        return false;
    }
}
