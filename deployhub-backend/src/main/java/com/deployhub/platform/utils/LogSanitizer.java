package com.deployhub.platform.utils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Masks secret-looking values before a log line is persisted. This is a safety net,
 * not a guarantee: it catches common shapes (KEY=value, URL credentials, bearer and
 * GitHub/AWS tokens), so secrets should still never be passed on command lines.
 */
public final class LogSanitizer {

    public static final int MAX_MESSAGE_LENGTH = 4000;
    private static final String MASK = "***";

    private record Rule(Pattern pattern, String replacement) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("(https?://)[^/\\s:@]+:[^/\\s@]+@"), "$1" + MASK + ":" + MASK + "@"),
            new Rule(Pattern.compile(
                    "(?i)\\b([\\w.-]*(?:password|passwd|secret|token|api[_-]?key|access[_-]?key)[\\w.-]*)(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;]+)"),
                    "$1$2" + MASK),
            new Rule(Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]+=*"), "Bearer " + MASK),
            new Rule(Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"), MASK),
            new Rule(Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"), MASK),
            new Rule(Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}"), MASK),
            new Rule(Pattern.compile("(?i)(authorization:\\s*basic\\s+)[A-Za-z0-9+/=]+"), "$1" + MASK),
            new Rule(Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), MASK));

    private LogSanitizer() {
    }

    public static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        String result = message;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result.length() > MAX_MESSAGE_LENGTH ? result.substring(0, MAX_MESSAGE_LENGTH) : result;
    }
}
