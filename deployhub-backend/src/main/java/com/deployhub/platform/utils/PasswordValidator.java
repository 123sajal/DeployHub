package com.deployhub.platform.utils;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 64;
    public static final int MAX_BYTES = 72;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }
        int length = value.codePointCount(0, value.length());
        return length >= MIN_LENGTH && length <= MAX_LENGTH && !value.isBlank() && fitsBcrypt(value);
    }

    public static boolean fitsBcrypt(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
