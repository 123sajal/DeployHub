package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.enums.EmailCodePurpose;

public interface IEmailCodeService {

    /** Emails a new 6-digit code (replacing older ones for this email and purpose). Limited per email and IP. */
    void send(EmailCodePurpose purpose, String email, Long userId, String ip);

    /** Uses the code; throws a field error ("code") when it is wrong, expired, used up or out of attempts. */
    void verify(EmailCodePurpose purpose, String email, Long userId, String code, String ip);
}
