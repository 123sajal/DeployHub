package com.deployhub.platform.utils;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SSRF guard: a future build worker will clone whatever URL is stored here, so we only
 * accept https URLs on a fixed allowlist of public Git hosts. That rules out
 * localhost, private IPs, cloud metadata endpoints (169.254.169.254), file:// and
 * credentials embedded in the URL.
 */
public class RepositoryUrlValidator implements ConstraintValidator<ValidRepositoryUrl, String> {

    static final Set<String> ALLOWED_HOSTS = Set.of("github.com", "gitlab.com", "bitbucket.org");

    private static final Pattern REPO_PATH = Pattern.compile("^(/[A-Za-z0-9_.-]+){2,}/?$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return isAllowed(value);
    }

    public static boolean isAllowed(String value) {
        if (value.length() > 300) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null
                || uri.getPort() != -1
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getHost() == null
                || !ALLOWED_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            return false;
        }
        String path = uri.getRawPath();
        return path != null && REPO_PATH.matcher(path).matches() && !path.contains("..");
    }
}
