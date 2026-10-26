package com.deployhub.platform.services.Interface;

import java.time.Duration;

public interface IMailService {

    /** Sends the code to the address. Failures are logged, not thrown (the caller never waits for email). */
    void sendPasswordResetCode(String email, String displayName, String code, Duration validFor);
}
