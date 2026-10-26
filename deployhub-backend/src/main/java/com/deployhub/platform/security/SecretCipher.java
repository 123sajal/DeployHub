package com.deployhub.platform.security;

import com.deployhub.platform.config.DeployHubProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secret values (environment variables) with AES-256-GCM before they are stored. GCM also
 * authenticates: a value changed in the database, or read with a different key, fails to decrypt instead
 * of returning garbage. The key comes from {@code deployhub.secrets.key} (DEPLOYHUB_SECRETS_KEY), never from
 * the database. Format: {@code v1:} + base64(12-byte random IV + ciphertext with tag); "v1" leaves room for
 * key rotation later.
 */
@Component
public class SecretCipher {

    private static final String VERSION = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(DeployHubProperties properties) {
        String encoded = properties.secrets() == null ? null : properties.secrets().key();
        if (encoded == null || encoded.isBlank()) {
            this.key = null;
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("DEPLOYHUB_SECRETS_KEY is not valid base64", e);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("DEPLOYHUB_SECRETS_KEY must be 32 bytes (base64 of 32 random bytes), got "
                    + bytes.length);
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return VERSION + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** @throws IllegalStateException when the value was tampered with or encrypted with another key */
    public String decrypt(String stored) {
        requireKey();
        if (stored == null || !stored.startsWith(VERSION)) {
            throw new IllegalStateException("Unknown secret format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(VERSION.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt a stored secret (was DEPLOYHUB_SECRETS_KEY changed?)", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("No encryption key: set DEPLOYHUB_SECRETS_KEY (openssl rand -base64 32)");
        }
    }
}
