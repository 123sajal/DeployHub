package com.deployhub.platform.models.enums;

public enum AccountStatus {
    ACTIVE,
    DISABLED,
    /** Created before Milestone 3 (the old development user); has no password and cannot log in. */
    LEGACY_NO_PASSWORD
}
