package com.deployhub.platform.utils;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 10-64 characters, not only whitespace, at most 72 UTF-8 bytes (BCrypt ignores anything
 * beyond 72 bytes, so longer input would give a false sense of strength).
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordValidator.class)
public @interface ValidPassword {

    String message() default "must be 10-64 characters (at most 72 bytes) and not only spaces";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
