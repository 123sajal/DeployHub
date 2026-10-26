package com.deployhub.platform.security;

import java.util.Locale;

/** One place that defines what "the same email" means: trimmed and lower-cased. */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
