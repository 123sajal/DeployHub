package com.deployhub.platform.security;

import com.deployhub.platform.config.DeployHubProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_B = Base64.getEncoder().encodeToString("another-32-byte-test-key-1234567".getBytes());

    private static SecretCipher cipher(String key) {
        return new SecretCipher(new DeployHubProperties(null, null, null, null, null, null, null,
                new DeployHubProperties.Secrets(key), null));
    }

    @Test
    void roundTripsAndNeverStoresThePlainValue() {
        SecretCipher cipher = cipher(KEY_A);
        String stored = cipher.encrypt("s3cret-password");

        assertThat(stored).startsWith("v1:").doesNotContain("s3cret");
        assertThat(cipher.decrypt(stored)).isEqualTo("s3cret-password");
        assertThat(cipher.encrypt("s3cret-password")).as("random IV every time").isNotEqualTo(stored);
    }

    @Test
    void aTamperedValueOrAnotherKeyFailsInsteadOfReturningGarbage() {
        String stored = cipher(KEY_A).encrypt("value");
        char[] chars = stored.toCharArray();
        chars[chars.length - 3] = chars[chars.length - 3] == 'A' ? 'B' : 'A';

        assertThatThrownBy(() -> cipher(KEY_A).decrypt(new String(chars))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(KEY_B).decrypt(stored))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DEPLOYHUB_SECRETS_KEY");
    }

    @Test
    void aMissingKeyIsReportedAndAWrongLengthKeyStopsStartup() {
        SecretCipher none = cipher("");
        assertThat(none.isConfigured()).isFalse();
        assertThatThrownBy(() -> none.encrypt("x")).hasMessageContaining("DEPLOYHUB_SECRETS_KEY");
        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }
}
