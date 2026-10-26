package com.deployhub.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Forgot password: emailed one-time codes.
 *
 * @param codeTtl              how long a code works
 * @param maxAttempts          guesses per code (the right one included); then a new code is needed
 * @param requestWindow        window over which code requests and wrong codes are counted
 * @param maxRequestsPerEmail  codes one email address may be sent within the window
 * @param maxRequestsPerIp     codes one IP may request within the window (for any addresses)
 * @param maxFailuresPerIp     wrong codes one IP may enter within the window
 */
@ConfigurationProperties(prefix = "deployhub.password-reset")
public record PasswordResetProperties(Duration codeTtl, int maxAttempts, Duration requestWindow,
                                      int maxRequestsPerEmail, int maxRequestsPerIp, int maxFailuresPerIp) {
}
