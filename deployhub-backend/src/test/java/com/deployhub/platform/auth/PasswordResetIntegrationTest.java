package com.deployhub.platform.auth;

import com.deployhub.platform.models.entities.PasswordResetCode;
import com.deployhub.platform.models.enums.AuditEventType;
import com.deployhub.platform.repositories.PasswordResetCodeRepository;
import com.deployhub.platform.services.Interface.IMailService;
import com.deployhub.platform.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Forgot password: an emailed 6-digit code (the mail service is replaced, so the test reads the code). */
class PasswordResetIntegrationTest extends IntegrationTestSupport {

    private static final String NEW_PASSWORD = "brand-new-passphrase";

    @MockitoBean IMailService mailService;
    @Autowired PasswordResetCodeRepository codeRepository;

    private ResultActions forgot(String email) throws Exception {
        return anonymousMvc.perform(post("/api/auth/forgot-password").with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"%s\"}".formatted(email)));
    }

    private ResultActions reset(String email, String code, String newPassword) throws Exception {
        return anonymousMvc.perform(post("/api/auth/reset-password").with(xsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"%s\"}".formatted(email, code, newPassword)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return anonymousMvc.perform(post("/api/auth/login").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    /** Requests a code for Alice and returns what was emailed (the n-th email in this test). */
    private String requestCodeForAlice(int nth) throws Exception {
        forgot(" Alice@Example.com ").andExpect(status().isAccepted());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(5000).times(nth))
                .sendPasswordResetCode(eq("alice@example.com"), eq("Alice"), code.capture(), eq(Duration.ofMinutes(10)));
        return code.getValue();
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    @Test
    void theEmailedCodeSetsANewPasswordOnceAndOnlyItsHashIsStored() throws Exception {
        String code = requestCodeForAlice(1);
        assertThat(code).matches("\\d{6}");
        PasswordResetCode stored = codeRepository.findAll().getFirst();
        assertThat(stored.getCodeHash()).startsWith("{bcrypt}").doesNotContain(code);

        reset("alice@example.com", code, NEW_PASSWORD).andExpect(status().isNoContent());

        login("alice@example.com", PASSWORD).andExpect(status().isUnauthorized());
        login("alice@example.com", NEW_PASSWORD).andExpect(status().isOk());
        reset("alice@example.com", code, "yet-another-passphrase")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").exists()); // used once, never again
        assertThat(auditEventRepository.findByUserIdOrderByIdAsc(alice.id()))
                .extracting(e -> e.getType())
                .contains(AuditEventType.PASSWORD_RESET_REQUESTED, AuditEventType.PASSWORD_RESET);
    }

    @Test
    void unknownEmailsGetTheSameAnswerAndNoEmail() throws Exception {
        forgot("nobody@example.com").andExpect(status().isAccepted());

        verify(mailService, after(300).never()).sendPasswordResetCode(anyString(), anyString(), anyString(), any());
        assertThat(codeRepository.count()).isZero();
        reset("nobody@example.com", "123456", NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").exists());
    }

    @Test
    void aCodeAllowsOnlyAFewGuessesThenEvenTheRightOneFails() throws Exception {
        String code = requestCodeForAlice(1);
        for (int i = 0; i < 5; i++) {
            reset("alice@example.com", wrong(code), NEW_PASSWORD).andExpect(status().isBadRequest());
        }
        reset("alice@example.com", code, NEW_PASSWORD).andExpect(status().isBadRequest());
        login("alice@example.com", PASSWORD).andExpect(status().isOk()); // password unchanged
    }

    @Test
    void anExpiredCodeFails() throws Exception {
        String code = requestCodeForAlice(1);
        PasswordResetCode stored = codeRepository.findAll().getFirst();
        stored.setExpiresAt(Instant.now().minusSeconds(1));
        codeRepository.save(stored);

        reset("alice@example.com", code, NEW_PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    void aNewCodeReplacesTheOldOne() throws Exception {
        String first = requestCodeForAlice(1);
        String second = requestCodeForAlice(2);
        assertThat(codeRepository.count()).isOne();

        if (!first.equals(second)) {
            reset("alice@example.com", first, NEW_PASSWORD).andExpect(status().isBadRequest());
        }
        reset("alice@example.com", second, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void requestsPerEmailAreLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            forgot("alice@example.com").andExpect(status().isAccepted());
        }
        forgot("alice@example.com").andExpect(status().isTooManyRequests());
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        reset("alice@example.com", "12ab", "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").exists())
                .andExpect(jsonPath("$.errors.newPassword").exists());
        forgot("not-an-email").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.email").exists());

        String code = requestCodeForAlice(1);
        reset("alice@example.com", code, "alice@example.com").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").value("must not be the same as your email"));
    }

    @Test
    void resettingEndsALoginLockout() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("alice@example.com", "wrong-password-123").andExpect(status().isUnauthorized());
        }
        login("alice@example.com", PASSWORD).andExpect(status().isTooManyRequests());

        reset("alice@example.com", requestCodeForAlice(1), NEW_PASSWORD).andExpect(status().isNoContent());

        login("alice@example.com", NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void theEndpointsNeedTheCsrfToken() throws Exception {
        anonymousMvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alice@example.com\"}")).andExpect(status().isForbidden());
        verify(mailService, never()).sendPasswordResetCode(anyString(), anyString(), anyString(), any());
    }
}
