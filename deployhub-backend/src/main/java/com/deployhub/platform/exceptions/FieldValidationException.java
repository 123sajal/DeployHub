package com.deployhub.platform.exceptions;

import lombok.Getter;

/** A validation rule that needs more than one field (e.g. "password must differ from email"). */
@Getter
public class FieldValidationException extends RuntimeException {

    private final String field;

    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
    }
}
