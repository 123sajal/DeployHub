package com.deployhub.platform.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMaskerTest {

    @Test
    void theProjectsOwnSecretsAreHiddenWhereverTheyAppear() {
        String log = "Connecting with password Xy7!pQ2 to db\nERROR: token=Xy7!pQ2 rejected; key abcd1234efgh";
        String masked = SecretMasker.mask(log, List.of("Xy7!pQ2", "abcd1234efgh"));
        assertThat(masked).doesNotContain("Xy7!pQ2").doesNotContain("abcd1234efgh").contains("******");
    }

    @Test
    void shortOrEmptyValuesAreLeftAloneSoTheTextStaysReadable() {
        assertThat(SecretMasker.mask("port 3000 started on dev", List.of("dev", "1", ""))).isEqualTo("port 3000 started on dev");
    }

    @Test
    void commonSecretShapesAreHiddenEvenWhenUnknown() {
        // Fake values, assembled at run time so secret scanners (e.g. GitHub push protection) don't mistake the
        // source for real keys.
        String keyBlock = "PRIVATE " + "KEY-----";
        String text = String.join("\n",
                "ANTHROPIC " + "sk-" + "ant-api03-abcdefghijklmnopqrstuvwxyz",
                "razorpay " + "rzp_" + "test_AbCdEf123456 and " + "rzp_" + "live_ZyXwVu987654",
                "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
                "mysql://root:hunter22@deployhub-mysql:3306/app",
                "Authorization: Bearer abc.def.ghi",
                "DB_PASSWORD=supersecret",
                "-----BEGIN RSA " + keyBlock,
                "MIIEowIBAAKCAQEA",
                "-----END RSA " + keyBlock);
        String masked = SecretMasker.mask(text);
        assertThat(masked)
                .doesNotContain("abcdefghijklmnopqrstuvwxyz")
                .doesNotContain("AbCdEf123456").doesNotContain("ZyXwVu987654")
                .doesNotContain("dozjgNryP4J3jVmNHl0w5N")
                .doesNotContain("hunter22").contains("mysql://")
                .doesNotContain("abc.def.ghi")
                .doesNotContain("supersecret").contains("DB_PASSWORD=")
                .doesNotContain("MIIEowIBAAKCAQEA");
    }

    @Test
    void lineBreaksAreKeptEvenAroundEmptyLines() {
        assertThat(SecretMasker.mask("\nfirst\n\nlast\n")).isEqualTo("\nfirst\n\nlast\n");
    }

    @Test
    void dollarSignsInSecretsDontBreakTheReplacement() {
        assertThat(SecretMasker.mask("pw $ecret$1 used", List.of("$ecret$1"))).isEqualTo("pw ****** used");
    }
}
