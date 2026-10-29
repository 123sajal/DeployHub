package com.deployhub.platform.assistant;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * A short-lived, signed note that says "this email was proven with a code", handed to the browser after the sign-up
 * code is checked and sent back with the registration. The key is random per process: after a restart old proofs
 * stop working and the user simply asks for a new code.
 */
@Component
public class EmailProofs {

    public static final Duration VALID_FOR = Duration.ofMinutes(30);

    private final byte[] key = new byte[32];
    private final Clock clock;

    public EmailProofs(Clock clock) {
        this.clock = clock;
        new SecureRandom().nextBytes(key);
    }

    public record Proof(String value, Instant expiresAt) {
    }

    public Proof issue(String email) {
        Instant expiresAt = clock.instant().plus(VALID_FOR);
        long expiry = expiresAt.getEpochSecond();
        return new Proof(expiry + "." + sign(email, expiry), expiresAt);
    }

    /** True if the proof was issued by this process for exactly this (normalised) email and hasn't expired. */
    public boolean isValid(String proof, String email) {
        if (proof == null || email == null) {
            return false;
        }
        int dot = proof.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        long expiry;
        try {
            expiry = Long.parseLong(proof.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        if (clock.instant().getEpochSecond() > expiry) {
            return false;
        }
        byte[] expected = sign(email, expiry).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, proof.substring(dot + 1).getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(String email, long expiry) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] signature = mac.doFinal((email + "|" + expiry).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
