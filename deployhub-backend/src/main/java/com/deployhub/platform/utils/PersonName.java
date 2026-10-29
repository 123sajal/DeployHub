package com.deployhub.platform.utils;

import java.util.regex.Pattern;

/**
 * A person's name: letters only (any language, e.g. "Sajal Tomar" or a Hindi name), single spaces between words; no
 * numbers or special characters.
 */
public final class PersonName {

    /** Also used by @Pattern on the sign-up request. \p{M} keeps the vowel signs of Indian scripts. */
    public static final String REGEX = "^\\p{L}[\\p{L}\\p{M}]*( [\\p{L}\\p{M}]+)*$";
    public static final String MESSAGE = "must contain only letters and spaces (no numbers or special characters)";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private PersonName() {
    }

    /** Trims and turns runs of spaces into one, so "  Sajal   Tomar " becomes "Sajal Tomar". */
    public static String normalize(String name) {
        return name == null ? null : name.strip().replaceAll("\\s+", " ");
    }

    public static boolean isValid(String name) {
        return name != null && PATTERN.matcher(name).matches();
    }
}
