package com.deployhub.platform.services.Interface;

import java.time.Duration;

public interface IMailService {

    /** Sends the code to the address. Failures are logged, not thrown (the caller never waits for email). */
    void sendPasswordResetCode(String email, String displayName, String code, Duration validFor);

    /** Sends a code that proves the address (sign-up or college email). Failures are logged, not thrown. */
    void sendEmailCode(String email, String purpose, String code, Duration validFor);
}
