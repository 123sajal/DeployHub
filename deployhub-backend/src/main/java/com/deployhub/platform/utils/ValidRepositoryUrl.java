package com.deployhub.platform.utils;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = RepositoryUrlValidator.class)
public @interface ValidRepositoryUrl {

    String message() default "must be an https URL to a repository on github.com, gitlab.com or bitbucket.org";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
