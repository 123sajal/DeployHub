package com.deployhub.platform.assistant;

import com.deployhub.platform.utils.LogSanitizer;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hides secrets before text goes to the AI. First the project's own secret values (env vars, the database password,
 * the repository token) wherever they appear, then the rules logs are saved with, then common secret shapes.
 */
public final class SecretMasker {

    public static final String MASK = "******";
    /** Shorter values (e.g. "1" or "dev") are too common to replace without mangling the text. */
    private static final int MIN_SECRET_LENGTH = 4;

    private record Rule(Pattern pattern, String replacement) {
    }

    private static final List<Rule> SHAPES = List.of(
            new Rule(Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{10,}"), MASK),
            new Rule(Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"), MASK),
            new Rule(Pattern.compile("\\brzp_(?:test|live)_[A-Za-z0-9]{6,}"), MASK),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), MASK),
            new Rule(Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"), MASK),
            new Rule(Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^/\\s:@]+:[^/\\s@]+@"), "$1" + MASK + "@"),
            // NAME=value where the name hints at a secret (SMTP_PASS, DB_PWD, API_KEY, AUTH_TOKEN, ...): the log rules
            // below only know the longer words, and the AI shouldn't see these values either.
            new Rule(Pattern.compile("(?i)\\b([\\w.-]*(?:pass|pwd|secret|token|key|credential|auth)[\\w.-]*)(\\s*[=:]\\s*)"
                    + "(\"[^\"]*\"|'[^']*'|[^\\s,;]+)"), "$1$2" + MASK));

    private SecretMasker() {
    }

    public static String mask(String text, Collection<String> secrets) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = text;
        for (String secret : secrets.stream().filter(s -> s != null && s.length() >= MIN_SECRET_LENGTH)
                .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            result = result.replace(secret, MASK);
        }
        // The rules logs are saved with first (they know "Bearer ..." and the like), then the shapes above.
        result = Arrays.stream(result.split("\n", -1)).map(LogSanitizer::sanitize).collect(Collectors.joining("\n"));
        for (Rule shape : SHAPES) {
            result = shape.pattern().matcher(result).replaceAll(shape.replacement());
        }
        return result;
    }

    public static String mask(String text) {
        return mask(text, List.of());
    }
}
